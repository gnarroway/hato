(defproject hato "0.0.0"
  :description "An HTTP client for Clojure, wrapping JDK 11's HttpClient, using muuntaja for content format negotiation."
  :url "https://github.com/gorillalabs/hato"
  :license {:name         "The MIT License"
            :url          "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :plugins [[com.roomkey/lein-v "7.0.0"]]
  :middleware [leiningen.v/version-from-scm
               leiningen.v/dependency-version-from-scm
               leiningen.v/add-workspace-data]
  :dependencies [[metosin/muuntaja "0.6.6"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [http-kit "2.3.0"]]}}
  :scm {:name "git"
        :url  "https://github.com/gorillalabs/hato"}

  ;; make sure you have your ~/.lein/credentials.clj.gpg setup correctly

  :release-tasks [["vcs" "assert-committed"]
                  ["v" "update"]
                  ["deploy" "clojars"]
                  ["vcs" "push"]])
