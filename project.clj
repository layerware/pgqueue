(defproject com.layerware/pgqueue "0.1.0"
  :description "durable queue implementation using postgresql advisory locks"
  :url "https://github.com/layerware/pgqueue"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.macro "0.1.2"]
                 [org.clojure/java.jdbc "0.3.7"]
                 [com.mchange/c3p0 "0.9.2.1"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [org.clojure/data.fressian "0.2.0"]
                 [com.taoensso/nippy "2.9.0"]]
  :profiles {:dev {:plugins [[lein-auto "0.1.2"]
                             [codox "0.8.13"]]
                   :codox {:src-dir-uri "http://github.com/layerware/pgqueue/blob/0.1.0/"
                           :src-linenum-anchor-prefix "L"
                           :output-dir "../gh-pages"}
                   :global-vars {*warn-on-reflection* false
                                 *assert* false}}})
