(ns bzzz.spam
  (use aleph.udp)
  (use [lamina core api])
  (:require [clojure.data.json :as json]))

(def socket-server (agent nil))
(defn create-server
  [host port]
    (udp-socket
      {:frame  (str :utf-8 :as-str true)
       :port port
       :address host}))

(defn start
  [host port receiver]
  (send socket-server #(or % (create-server host port)))
  (receive-all @socket-server receiver))

(defn stop
  []
  (send socket-server close)
  (shutdown-agents))
