(ns pgqueue.example.puter
  (:require [pgqueue :as pgq]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]))

(defn -main []
  (let [p (System/getenv "PGQUEUE_CONFIG")
        c (edn/read-string (slurp (io/file p)))
        q (pgq/queue :example c)]
    (loop [id 0]
      (let [p (rand-int 500)
            i {:id id
               :priority p
               :inserted (System/currentTimeMillis)}
            r (pgq/put q p i)]
        (when r
          (println (format "Put: %-7d priority: %-5d"
                     (:id i)
                     (:priority i)))))
      (Thread/sleep 10)     
      (recur (inc id)))))
