(defproject pgqueue-perf "0.1.0-SNAPSHOT"
  :description "pgqueue perf"
  :url "https://github.com/layerware/pgqueue/perf"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.layerware/pgqueue "0.2.0-SNAPSHOT"]]
  :jvm-opts ^:replace ["-Xmx1g" "-Xms1g" "-server" ])
