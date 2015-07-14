(ns pgqueue.example.puter
  (:require [pgqueue :as pgq]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]))

(defn -main []
  (let [p (System/getenv "PGQUEUE_CONFIG")
        c (edn/read-string (slurp (io/file p)))
        q (pgq/queue :example c)]
    (loop [id 0]
      (let [i {:id id
               :priority (rand-int 500)
               :inserted (System/currentTimeMillis)}
            r (pgq/put q i)]
        (when r (println (str "Put: " (:id i)
                           " w/ priority: " (:priority i)
                           " at: " (:inserted i)))))
      (Thread/sleep 10)     
      (recur (inc id)))))
