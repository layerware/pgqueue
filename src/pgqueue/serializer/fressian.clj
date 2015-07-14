(ns pgqueue.serializer.fressian
  (:require [pgqueue.serializer.protocol :as s]
            [clojure.data.fressian :as fressian])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [org.fressian.handlers WriteHandler ReadHandler]))

; Adding vec reader/writer (how does this not already exist?)
; TODO: not sure if this is actually working all the time

(def ^:private vec-writer
  (reify WriteHandler
    (write [_ writer thevec]
      (.writeTag writer "vec" 1)
      (.writeList writer thevec))))

(def ^:private vec-reader
  (reify ReadHandler
    (read [_ reader tag component-count]
      (vec (.readObject reader)))))

(def ^:private write-handlers
  (-> (merge {clojure.lang.PersistentVector {"vec" vec-writer}}
        fressian/clojure-write-handlers)
    fressian/associative-lookup
    fressian/inheritance-lookup))

(def ^:private read-handlers
  (-> (merge {"vec" vec-reader} fressian/clojure-read-handlers)
    fressian/associative-lookup))

(deftype FressianSerializer []
  s/Serializer
  (serialize [this object]
    (let [os (ByteArrayOutputStream.)
          wr (fressian/create-writer os :handlers write-handlers)]
      (fressian/write-object wr object)
      (.toByteArray os)))
  (deserialize [this bytes]
    (if (nil? bytes) nil
        (let [is (ByteArrayInputStream. bytes)
              rd (fressian/create-reader is :handlers read-handlers)]
          (fressian/read-object rd)))))

(defn fressian-serializer []
  (->FressianSerializer))

