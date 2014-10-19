(ns bzzz.index
  (use bzzz.analyzer)
  (use bzzz.util)
  (use bzzz.const)
  (use bzzz.query)
  (:require [clojure.tools.logging :as log])
  (:import (java.io StringReader File)
           (org.apache.lucene.document Document Field Field$Index Field$Store)
           (org.apache.lucene.index IndexWriter IndexReader Term
                                    IndexWriterConfig DirectoryReader FieldInfo)
           (org.apache.lucene.search Query ScoreDoc SearcherManager IndexSearcher
                                     Explanation Collector TopScoreDocCollector TopDocsCollector)
           (org.apache.lucene.store NIOFSDirectory Directory)))

(def root* (atom default-root))
(def mapping* (atom {}))
(defn acceptable-index-name [name]
  (clojure.string/replace name #"[^a-zA-Z_0-9-]" ""))

(defn new-index-directory ^Directory [name]
  (NIOFSDirectory. (File. (File. (as-str @root*)) (as-str (acceptable-index-name name)))))

(defn new-index-writer ^IndexWriter [name]
  (IndexWriter. (new-index-directory name)
                (IndexWriterConfig. *version* @analyzer*)))

(defn new-index-reader ^IndexReader [name]
  (with-open [writer (new-index-writer name)]
    (DirectoryReader/open ^IndexWriter writer false)))

(defn index? [name]
  (if (or (substring? "_index" name)
          (= name id-field))
    true
    false))

(defn analyzed? [name]
  (if (or (substring? "_not_analyzed" name)
          (= name id-field))
    false
    true))

(defn norms? [name]
  (if (or (substring? "_no_norms" name)
          (= name id-field))
    false
    true))

(defn- add-field [document key value]
  (let [ str-key (as-str key) ]
    (.add ^Document document
          (Field. str-key (as-str value)
                  (if (or
                       (substring? "_store" str-key)
                       (= str-key id-field))
                    Field$Store/YES
                    Field$Store/NO)
                  (if (index? str-key)
                    (case [(analyzed? str-key) (norms? str-key)]
                      [true true] Field$Index/ANALYZED
                      [true false] Field$Index/ANALYZED_NO_NORMS
                      [false true] Field$Index/NOT_ANALYZED
                      [false false] Field$Index/NOT_ANALYZED_NO_NORMS)
                    Field$Index/NO)))))

(defn map->document
  [hmap]
  (let [document (Document.)]
    (doseq [[key value] hmap]
      (add-field document (as-str key) value))
    document))

(defn document->map
  [^Document doc score ^Explanation explanation]
  (conj
   (into {:_score score }
         (for [^Field f (.getFields doc)]
           [(keyword (.name f)) (.stringValue f)]))
   (when explanation { :_explain (.toString explanation) })))

(defn get-search-manager ^SearcherManager [index]
  (locking mapping*
    (when (nil? (@mapping* index))
      (swap! mapping* assoc index (SearcherManager. (new-index-directory index)
                                                    nil)))
    (@mapping* index)))

(defn refresh-search-managers []
  (locking mapping*
    (doseq [[index ^SearcherManager manager] @mapping*]
      (log/info "refreshing: " index " " manager)
      (.maybeRefresh manager))))

(defn bootstrap-indexes []
  (doseq [f (.listFiles (File. (as-str @root*)))]
    (if (.isDirectory ^File f)
      (get-search-manager (.getName ^File f)))))

(defn use-searcher [index callback]
  (let [manager (get-search-manager index)
        searcher (.acquire manager)]
    (try
      (callback searcher)
      (finally (.release manager searcher)))))

(defn use-writer [index callback]
  (let [writer (new-index-writer index)]
    (try
      (callback ^IndexWriter writer)
      (finally
        (.commit writer)
        (.forceMerge writer 1)
        (.close writer)))))

(defn store
  [index maps analyzer]
  (if analyzer
    (reset! analyzer* (parse-analyzer analyzer)))
  (use-writer index (fn [^IndexWriter writer]
                      (doseq [m maps]
                        (if (:id m)
                          (.updateDocument writer ^Term (Term. ^String id-field
                                                               (as-str (:id m))) (map->document m))
                          (.addDocument writer (map->document m))))
                      { index true })))

(defn delete-from-query
  [index input]
  (use-writer index (fn [^IndexWriter writer]
                      ;; method is deleteDocuments(Query...)
                      (let [query (parse-query input)]
                        (.deleteDocuments writer ^"[Lorg.apache.lucene.search.Query;" (into-array Query [query]))
                        { index (.toString query) }))))

(defn delete-all [index]
  (use-writer index (fn [^IndexWriter writer] (.deleteAll writer))))

(defn search
  [& {:keys [index query page size explain]
      :or {page 0, size 20, explain false}}]
  (use-searcher index
                (fn [^IndexSearcher searcher]
                  (let [query (parse-query query)
                        pq-size (+ (* page size) size)
                        collector ^TopDocsCollector (TopScoreDocCollector/create pq-size true)]
                    (.search searcher query collector)
                    {:total (.getTotalHits collector)
                     :hits (into []
                                 (for [^ScoreDoc hit (-> (.topDocs collector (* page size)) (.scoreDocs))]
                                   (document->map (.doc searcher (.doc hit))
                                                  (.score hit)
                                                  (when explain
                                                    (.explain searcher query (.doc hit))))))}))))

(defn shutdown []
  (locking mapping*
    (log/info "executing shutdown hook, current mapping: " @mapping*)
    (doseq [[name ^SearcherManager manager] @mapping*]
      (log/info "\tclosing: " name " " manager)
      (.close manager))
    (reset! mapping* {})
    (log/info "mapping after cleanup: " @mapping*)))
