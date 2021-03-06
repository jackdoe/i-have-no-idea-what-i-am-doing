(ns bzzz.const
  (:import (org.apache.lucene.util Version)))

(def ^{:dynamic true :tag Version} *version* Version/LUCENE_CURRENT)
(def id-field "id")
(def default-root "/tmp/BZZZ")
(def default-port 3000)
(def default-size 20)
(def default-http-threads 24)
(def default-allow-unsafe-queries false)
(def default-identifier :__global_partition_0)
(def default-acceptable-discover-time-diff 20)
(def location-field "__location")
