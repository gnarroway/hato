(defproject hato "0.1.0-SNAPSHOT"
  :description "An HTTP client for Clojure, wrapping JDK 11's HttpClient."
  :url "https://github.com/gnarroway/hato"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [cheshire "5.8.1"]
                                  [com.cognitect/transit-clj "0.8.313"]]}})
