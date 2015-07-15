(ns pgqueue.perf
  (:require [pgqueue :as pgq]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]))

(defn ms [milliseconds]
  (format "%9dms" milliseconds))

(defn now-diff [start]
  (- (System/currentTimeMillis) start))

(defn avg [n tm]
  (format "%7.3f/ms" (/ (float n) tm)))

(defn print-timings [n start]
  (let [diff (now-diff start)]
      (println (ms diff) "duration" (avg n diff) "avg rate")))

(defn int-run  [q n]
  (let [workers 32
        start (System/currentTimeMillis)]

    (print (format "\nPut  %7d integers..." n))
    (doall (pmap #(pgq/put q %) (range n)))
    (print-timings n start)
    

    (print (format "Take %7d integers..." n))
    (let [work (repeat workers
                 (future
                   (doall (take-while #(not (nil? %))
                            (repeatedly #(pgq/take q))))))]
      (doall (map deref work)))
    (print-timings n start)

    ; make sure we actually took!
    (assert (= 0 (pgq/count q)))))

(defn -main []
  (let [p (System/getenv "PGQUEUE_CONFIG")
        c (edn/read-string (slurp (io/file p)))
        _ (pgq/destroy-all-queues! c)
        q (pgq/queue :perf c)]

    (println "pgqueue perf test")
    (int-run q 100)
    (int-run q 1000)
    (int-run q 10000)
    )
  (System/exit 1))
