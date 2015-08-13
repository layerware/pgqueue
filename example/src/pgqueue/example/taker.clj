(ns pgqueue.example.taker
  (:require [pgqueue.core :as pgq]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]))

(defn -main []
  (let [p (System/getenv "PGQUEUE_CONFIG")
        c (edn/read-string (slurp (io/file p)))
        q (pgq/queue :example c)]
    (loop []
      (let [i (pgq/take q)]
        (if i
          (println (format "Took: %-7d priority: %-5d in queue (ms): %-7d"
                     (:id i)
                     (:priority i)
                     (- (System/currentTimeMillis) (:inserted i))))
          ;; if q was empty, sleep a bit before checking again
          (do (println "empty queue")
              (Thread/sleep 100))))
      (recur))))
