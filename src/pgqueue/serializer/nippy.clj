(ns pgqueue.serializer.nippy
  (:require [pgqueue.serializer.protocol :as s]
            [taoensso.nippy :as nippy]))

(deftype NippySerializer []
  s/Serializer
  (serialize [this object]
    (nippy/freeze object))
  (deserialize [this bytes]
    (if (nil? bytes) nil (nippy/thaw bytes))))

(defn nippy-serializer []
  (->NippySerializer))
