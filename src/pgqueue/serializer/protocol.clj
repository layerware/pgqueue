(ns pgqueue.serializer.protocol)

(defprotocol Serializer
  "Serializer/Deserializer to/from byte array"
  (serialize [this object])
  (deserialize [this bytes]))
