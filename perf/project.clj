(defproject pgqueue-perf "0.5.1-SNAPSHOT"
  :description "pgqueue perf"
  :url "https://github.com/layerware/pgqueue/perf"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.layerware/pgqueue "0.5.1"]]
  :jvm-opts ^:replace ["-Xmx1g" "-Xms1g" "-server"]
  :global-vars {*warn-on-reflection* false
                *assert* true})
