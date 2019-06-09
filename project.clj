(defproject hato "0.1.0-SNAPSHOT"
  :description "An HTTP client for Clojure, wrapping JDK 11's HttpClient."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]]
  :profiles {:dev {:dependencies [[cheshire "5.8.1"]
                                  [com.cognitect/transit-clj "0.8.313"]]}})
