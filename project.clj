(defproject com.layerware/pgqueue "0.5.1"
  :description "durable queue implementation using postgresql advisory locks"
  :url "https://github.com/layerware/pgqueue"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/java.jdbc "0.7.5"]
                 [org.postgresql/postgresql "42.2.2"]
                 [org.clojure/data.fressian "0.2.1"]
                 [com.taoensso/nippy "2.14.0"]]
  :profiles {:dev {:plugins [[lein-auto "0.1.3"]
                             [lein-codox "0.10.3"]]
                   :codox {:source-uri "http://github.com/layerware/pgqueue/blob/0.5.1/{filepath}#L{line}"
                      :output-path "../gh-pages"
                      :source-paths ["src"]}
                   :global-vars {*warn-on-reflection* false
                                 *assert* false}}})
