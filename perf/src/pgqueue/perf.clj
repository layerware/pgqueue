(ns pgqueue.perf
  (:require [pgqueue :as pgq]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]))

(defn ms [milliseconds]
  (format "%9dms" milliseconds))

(defn now-diff [start]
  (- (System/currentTimeMillis) start))

(defn int-run  [q n]
  (let [start (System/currentTimeMillis)]
    (print (format "Put  %7d integers..." n))
    (let [pool (map (fn [i] (future (pgq/put q i))) (range n))]
      (doall (map deref pool)))
    (println (ms (now-diff start)))

    (print (format "Take %7d integers..." n))
    (let [pool (map (fn [i] (future (pgq/take q))) (range n))]
      (doall (map deref pool)))
    (println (ms (now-diff start)))))

(defn -main []
  (let [p (System/getenv "PGQUEUE_CONFIG")
        c (edn/read-string (slurp (io/file p)))
        _ (pgq/destroy-all-queues! c)
        q (pgq/queue :perf c)]

    (int-run q 100)
    (int-run q 1000)
    (int-run q 10000))
  (System/exit 1))
