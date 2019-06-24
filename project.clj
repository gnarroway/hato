(defproject hato "0.2.0"
  :description "An HTTP client for Clojure, wrapping JDK 11's HttpClient."
  :url "https://github.com/gnarroway/hato"
  :license {:name         "The MIT License"
            :url          "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :deploy-repositories [["clojars" {:url           "https://clojars.org/repo"
                                      :username      :env/clojars_user
                                      :password      :env/clojars_pass
                                      :sign-releases false}]]
  :plugins [[lein-cljfmt "0.6.4"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [cheshire "5.8.1"]
                                  [com.cognitect/transit-clj "0.8.313"]]}})
