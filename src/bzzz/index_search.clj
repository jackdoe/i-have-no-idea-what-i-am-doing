(ns bzzz.index-search
  (use bzzz.analyzer)
  (use bzzz.util)
  (use bzzz.const)
  (use bzzz.query)
  (use bzzz.index-facet-common)
  (use bzzz.index-directory)
  (use bzzz.random-score-query)
  (:require [clojure.tools.logging :as log])
  (:import (java.io StringReader)
           (org.apache.lucene.facet FacetsConfig FacetField FacetsCollector LabelAndValue)
           (org.apache.lucene.facet.taxonomy FastTaxonomyFacetCounts)
           (org.apache.lucene.facet.taxonomy.directory DirectoryTaxonomyReader)
           (org.apache.lucene.analysis Analyzer TokenStream)
           (org.apache.lucene.document Document)
           (org.apache.lucene.search.highlight Highlighter QueryScorer
                                               SimpleHTMLFormatter TextFragment)
           (org.apache.lucene.index IndexReader Term IndexableField)
           (org.apache.lucene.search Query ScoreDoc SearcherManager IndexSearcher
                                     Explanation Collector TopScoreDocCollector
                                     TopDocsCollector MultiCollector FieldValueFilter)))

(defn document->map
  [^Document doc only-fields score highlighter ^Explanation explanation]
  (let [m (into {:_score score }
                (for [^IndexableField f (.getFields doc)]
                  (let [str-name (.name f)
                        name (keyword str-name)]
                    (if (or (nil? only-fields)
                            (name only-fields))
                      (let [values (.getValues doc str-name)]
                        (if (= (count values) 1)
                          [name (first values)]
                          [name (vec (rest values))]))))))
        highlighted (highlighter m)]
    (conj
     m
     (when explanation (assoc m :_explain (.toString explanation)))
     (when highlighted (assoc m :_highlight highlighted)))))

(defn fragment->map [^TextFragment fragment fidx]
  {:text (.toString fragment)
   :score (.getScore fragment)
   :index fidx
   :frag-num (wall-hack-field (class fragment) :fragNum fragment)
   :text-start-pos (wall-hack-field (class fragment) :textStartPos fragment)
   :text-end-pos (wall-hack-field (class fragment) :textEndPos fragment)})

(defn get-best-fragments [str field highlighter analyzer max-fragments fidx]
  (map #(fragment->map % fidx)
       (.getBestTextFragments ^Highlighter highlighter
                              ^TokenStream (.tokenStream ^Analyzer analyzer
                                                         (as-str field)
                                                         (StringReader. str))
                              ^String str
                              true
                              (int max-fragments))))

(defn make-highlighter
  [^Query query ^IndexSearcher searcher config analyzer]
  (if config
    (let [indexReader (.getIndexReader searcher)
          scorer (QueryScorer. (.rewrite query indexReader))
          config (merge {:max-fragments 5
                         :pre "<b>"
                         :post "</b>"}
                        config)
          {:keys [fields max-fragments separator fragments-key pre post use-text-fragments]} config
          highlighter (Highlighter. (SimpleHTMLFormatter. pre post) scorer)]
      (fn [m]
        (into {} (for [field fields]
                   [(keyword field)
                    (if-let [value ((keyword field) m)]
                      (flatten (into [] (for [[fidx str] (indexed (if (vector? value) value [value]))]
                                          (vec (get-best-fragments str
                                                                   field
                                                                   highlighter
                                                                   analyzer
                                                                   max-fragments
                                                                   fidx)))))
                      [])]))))
    (constantly nil)))


(defn get-score-collector ^TopDocsCollector [sort pq-size]
  (TopScoreDocCollector/create pq-size true))

(defn limit [input hits sort-key]
  (let [size (get input :size default-size)
        sorted (sort-by sort-key #(compare %2 %1) hits)]
    (if (and  (> (count hits) size)
              (get input :enforce-limits true))
      (subvec (vec sorted) 0 size)
      sorted)))

(defn concat-facets [big small]
  (if (not big)
    small ;; initial reduce
    (into big
          (for [[k v] small]
            (if-let [big-list (get big k)]
              [k (concat v big-list)]
              [k v])))))

(defn input-facet-settings [input dim]
  (let [global-ef (get-in input [:enforce-limits] true)
        config (get-in input [:facets (keyword dim)] {})]
    (if (contains? config :enforce-limits)
      config
      (assoc config :enforce-limits global-ef))))

(defn merge-facets [facets]
  ;; produces not-sorted output
  (into {}
        (for [[k v] (default-to facets {})]
          [(keyword k) (vals (reduce (fn [sum next]
                                       (let [l (:label next)]
                                         (if (contains? sum l)
                                           (update-in sum [l :count] + (:count next))
                                           (assoc sum l next))))
                                     {}
                                     v))])))

(defn merge-and-limit-facets [input facets]
  (into {} (for [[k v] (merge-facets facets)]
             ;; this is broken by design
             ;; :__shard_2 {:facets {:name [{:label "jack doe"
             ;;                              :count 100}
             ;;                             {:label "john doe"
             ;;                              :count 10}]}}
             ;;                          ;; -----<cut>-------
             ;;                          ;; {:label "foo bar"
             ;;                          ;; :count 8}
             ;;
             ;; :__shard_3 {:facets {:name [{:label "foo bar"
             ;;                              :count 9}]}}}
             ;;
             ;; so when the multi-search merge happens
             ;; with size=2,it will actully return only
             ;; 'jack doe(100)' and 'john doe(10)' even though
             ;; the actual count of 'foo bar' is 17, because
             ;; __shard_2 actually didnt even send 'foo bar'
             ;; because of the size=2 cut
             [k (limit (input-facet-settings input (keyword k))
                       v
                       :count)])))


(defn result-reducer [sum next]
  (let [next (if (future? next)
               (try
                 @next
                 (catch Throwable e
                   {:exception (as-str e)}))
               next)
        ex (if (:exception next)
             (if-not (:can-return-partial sum)
               (throw (Throwable. (as-str (:exception next))))
               (do
                 (log/info (str "will send partial response: " (as-str (:exception next))))
                 (as-str (:exception next))))
             nil)]
    (-> sum
        (update-in [:failed] conj-if ex)
        (update-in [:failed] concat-if (:failed next))
        (update-in [:facets] concat-facets (get next :facets {}))
        (update-in [:total] + (get next :total 0))
        (update-in [:hits] concat (get next :hits [])))))

(defn reduce-collection [collection input ms-start]
  (let [result (reduce result-reducer
                       {:total 0
                        :hits []
                        :facets {}
                        :took -1
                        :failed []
                        :can-return-partial (get input :can-return-partial false)}
                       collection)]
    (-> result
        (assoc-in [:facets] (merge-and-limit-facets input (:facets result)))
        (assoc-in [:hits] (limit input (:hits result) :_score))
        (assoc-in [:took] (time-took ms-start)))))

(defn shard-search
  [& {:keys [^IndexSearcher searcher ^DirectoryTaxonomyReader taxo-reader
             ^Query query ^Analyzer analyzer
             page size explain highlight facets fields facet-config]
      :or {page 0, size default-size, explain false,
           analyzer nil, facets nil, fields nil}}]
  (let [ms-start (time-ms)
        highlighter (make-highlighter query searcher highlight analyzer)
        pq-size (+ (* page size) size)
        score-collector (get-score-collector sort pq-size)
        facet-collector (FacetsCollector.)
        wrap (MultiCollector/wrap
              ^"[Lorg.apache.lucene.search.Collector;"
              (into-array Collector
                          [score-collector
                           facet-collector]))]
    (.search searcher
             query
             nil
             wrap)
    {:total (.getTotalHits score-collector)
     :facets (if taxo-reader
               (try
                 (let [fc (FastTaxonomyFacetCounts. taxo-reader
                                                    facet-config
                                                    facet-collector)]
                   (into {} (for [[k v] facets]
                              (if-let [fr (.getTopChildren fc
                                                           (get v :size default-size)
                                                           (as-str k)
                                                           ^"[Ljava.lang.String;" (into-array
                                                                                   String []))]
                                [(keyword (.dim fr))
                                 (into [] (for [^LabelAndValue lv (.labelValues fr)]
                                            {:label (.label lv)
                                             :count (.value lv)}))]))))
                 (catch Throwable e
                   (let [ex (ex-str e)]
                     (log/warn (ex-str e))
                     {}))) ;; do not send the error back,
               {}) ;; no taxo reader, probably problem with open, exception is thrown
                   ;; even though we might fake a facet result
                   ;; it could really surprise the client
     :hits (into []
                 (for [^ScoreDoc hit (-> (.topDocs score-collector (* page size)) (.scoreDocs))]
                   (document->map (.doc searcher (.doc hit))
                                  fields
                                  (.score hit)
                                  highlighter
                                  (when explain
                                    (.explain searcher query (.doc hit))))))
     :took (time-took ms-start)}))

(defn search [input]
  (let [ms-start (time-ms)
        index (need :index input "need index")
        page (get input :page 0)
        size (get input :size default-size)
        explain (get input :explain false)
        highlight (:highlight input)
        fields (:fields input)
        analyzer (extract-analyzer (:analyzer input))
        query (parse-query (:query input) analyzer)
        facets (:facets input)
        facet-config (get-facet-config facets)
        futures (into [] (for [shard (index-name-matching index)]
                           (future (use-searcher shard
                                                 (fn [^IndexSearcher searcher ^DirectoryTaxonomyReader taxo-reader]
                                                   (shard-search :searcher searcher
                                                                 :taxo-reader taxo-reader
                                                                 :analyzer analyzer
                                                                 :facet-config facet-config
                                                                 :highlight highlight
                                                                 :query query
                                                                 :page page
                                                                 :size size
                                                                 :facets facets
                                                                 :explain explain
                                                                 :fields fields))))))]
    (reduce-collection futures input ms-start)))