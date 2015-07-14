(ns pgqueue.example.taker
  (:require [pgqueue :as pgq]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]))

(defn -main []
  (let [p (System/getenv "PGQUEUE_CONFIG")
        c (edn/read-string (slurp (io/file p)))
        q (pgq/queue :example c)]
    (loop []
      (let [i (pgq/take q)]
        (if i
          (println (str "Took: " (:id i)
                     " w/ priority: " (:priority i)
                     " ms in queue: " (- (System/currentTimeMillis) (:inserted i))))
                                        ; if q was empty, sleep a bit before checking again
          (do (println "empty queue")
              (Thread/sleep 100))))
      (recur))))
