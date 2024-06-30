
(defproject hato "1.1.0-SNAPSHOT"
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
  :global-vars {*warn-on-reflection* true}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.11.1"]
                                  [org.clojure/data.json "2.4.0"]
                                  [cheshire "5.10.0"]
                                  [ring/ring-core "1.9.0"]
                                  [javax.servlet/servlet-api "2.5"]
                                  [com.cognitect/transit-clj "0.8.319"]
                                  [funcool/promesa "6.0.0"]
                                  [http-kit "2.3.0"]]}})
