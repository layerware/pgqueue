(defproject com.layerware/pgqueue "0.5.0"
  :description "durable queue implementation using postgresql advisory locks"
  :url "https://github.com/layerware/pgqueue"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.postgresql/postgresql "9.4.1208"]
                 [org.clojure/data.fressian "0.2.1"]
                 [com.taoensso/nippy "2.11.1"]]
  :profiles {:dev {:plugins [[lein-auto "0.1.2"]
                             [lein-codox "0.9.5"]]
                   :codox {:source-uri "http://github.com/layerware/pgqueue/blob/0.5.0/{filepath}#L{line}"
                      :output-path "../gh-pages"
                      :source-paths ["src"]}
                   :global-vars {*warn-on-reflection* false
                                 *assert* false}}})
