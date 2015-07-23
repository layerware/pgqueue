(ns pgqueue.perf
  (:require [pgqueue :as pgq]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]))

(def ^:private ^:dynamic *takes* (atom 0))

(def num-workers (+ 2 (.. Runtime getRuntime availableProcessors))  )

(defn ms [milliseconds]
  (format "%7dms" milliseconds))

(defn now-diff [start]
  (- (System/currentTimeMillis) start))

(defn avg [n tm]
  (format "%7.3f/ms (%6.0f/s)"
    (/ (float n) tm)
    (* 1000 (/ (float n) tm))))

(defn print-timings [n start]
  (let [diff (now-diff start)]
    (println (ms diff) "duration" (avg n diff) "avg rate")))

(defn int-run  [q n]
  (println)
  ;; (print (format "       put %7d integers..." n))
  ;; (let [start (System/currentTimeMillis)
  ;;       data (partition num-workers (range n))
  ;;       work (map (fn [data-part]
  ;;                   (future
  ;;                     (doall (take-while #(pgq/put q %) data-part)))) data)]
  ;;   (doall (map deref work))
  ;;   (print-timings n start))
  

  ;; (print (format "      take %7d integers..." n))
  ;; (let [start (System/currentTimeMillis)
  ;;       work (repeat num-workers
  ;;              (future
  ;;                (doall (take-while #(not (nil? %))
  ;;                         (repeatedly #(pgq/take q))))))]
  ;;   (doall (map deref work))
  ;;   (print-timings n start))

  (print (format " put-batch %7d integers..." n))
  (let [start (System/currentTimeMillis)]
    (pgq/put-batch q (range n))
    (print-timings n start))

  (print (format "take-batch %7d integers..." n))
  (let [start (System/currentTimeMillis)]
    (assert (= n (count (pgq/take-batch q n))))
    (print-timings n start))

    ;; make sure we actually took!

  (assert (= 0 (pgq/count q))))

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

  (shutdown-agents))
