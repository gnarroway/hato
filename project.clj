(defproject hato "0.1.0-SNAPSHOT"
  :description "An HTTP client for Clojure, wrapping JDK 11's HttpClient."
  :url "https://github.com/gnarroway/hato"
  :license {:name         "The MIT License"
            :url          "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :deploy-repositories [["snapshots" {:url           "https://clojars.org/repo"
                                      :username      :env/clojars_user
                                      :password      :env/clojars_pass
                                      :sign-releases false}]
                        ["releases" {:url           "https://clojars.org/repo"
                                     :username      :env/clojars_user
                                     :password      :env/clojars_pass
                                     :sign-releases false}]]
  :plugins [[lein-cljfmt "0.6.4"]]
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v" "--no-sign"] ; disable signing and add "v" prefix
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [cheshire "5.8.1"]
                                  [com.cognitect/transit-clj "0.8.313"]]}})
