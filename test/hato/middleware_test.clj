(ns hato.middleware-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [hato.middleware :refer :all]
            [muuntaja.core :as m])
  (:import (java.util.zip
             GZIPOutputStream)
           (java.io ByteArrayOutputStream InputStream)))

(deftest test-wrap-request-timing
  (let [r ((wrap-request-timing (fn [x] (Thread/sleep 1) x)) {})]
    (is (< 0 (:request-time r) 10))))

(deftest test-wrap-query-params
  (testing "with no query-params"
    (let [r ((wrap-query-params identity) {})]
      (is (= {} r))))

  (testing "with some query-params"
    (let [q {:moo "cow" :foo "bar"}
          r ((wrap-query-params identity) {:query-params q})]
      (is (not (contains? r :query-params)))
      (is (= "moo=cow&foo=bar" (:query-string r)))))

  (testing "with url encoding"
    (let [q {:q "a-space and-some-chars$&!"}
          r ((wrap-query-params identity) {:query-params q})]
      (is (= "q=a-space+and-some-chars%24%26%21" (:query-string r)))))

  (testing "with multi-param-style"
    (let [q {:a [1 2]}]
      (are [expected style] (= expected (:query-string ((wrap-query-params identity) {:query-params q :multi-param-style style})))
                            "a=1&a=2" nil
                            "a=1&a=2" :some-unrecognised
                            "a[0]=1&a[1]=2" :indexed
                            "a[]=1&a[]=2" :array))))

(deftest test-wrap-nested-params
  (let [params {:a {:b {:c 5} :e {:f 6}}}
        flattened {"a[b][c]" 5, "a[e][f]" 6}]

    (testing "query params"
      (testing "nests by default"
        (is (= flattened (:query-params ((wrap-nested-params identity) {:query-params params})))))

      (testing "can be disabled"
        (is (= params (:query-params ((wrap-nested-params identity) {:query-params params :ignore-nested-query-string true})))))

      (testing "can be enabled with flatten-nested-keys"
        (is (= flattened (:query-params ((wrap-nested-params identity) {:query-params params :flatten-nested-keys [:query-params]})))))

      (testing "throws if multiple methods specified"
        (is (thrown? IllegalArgumentException ((wrap-nested-params identity) {:query-params               params
                                                                              :ignore-nested-query-string true
                                                                              :flatten-nested-keys        [:query-params]})))))

    (testing "form params"
      (testing "does not nest by default"
        (is (= params (:form-params ((wrap-nested-params identity) {:form-params params})))))

      (testing "can be enabled"
        (is (= flattened (:form-params ((wrap-nested-params identity) {:form-params params :flatten-nested-form-params true})))))

      (testing "can be enabled with flatten-nested-keys"
        (is (= flattened (:form-params ((wrap-nested-params identity) {:form-params params :flatten-nested-keys [:form-params]})))))

      (testing "throws if multiple methods specified"
        (is (thrown? IllegalArgumentException ((wrap-nested-params identity) {:form-params                params
                                                                              :flatten-nested-form-params true
                                                                              :flatten-nested-keys        [:form-params]})))))))

(deftest test-wrap-basic-auth
  (testing "encoding"
    (is (= "Basic dXNlcm5hbWU6cGFzc3dvcmQ=" (basic-auth-value {:user "username" :pass "password"})))
    (is (= "Basic dXNlcm5hbWU6" (basic-auth-value {:user "username"})))
    (is (= "Basic Og==" (basic-auth-value {}))))

  (testing "with no basic-auth option"
    (is (not (contains? ((wrap-basic-auth identity) {}) :headers))))

  (testing "with basic-auth option"
    (let [r ((wrap-basic-auth identity) {:basic-auth {:user "username" :pass "password"}})]
      (is (not (contains? r :basic-auth)))
      (is (= "Basic dXNlcm5hbWU6cGFzc3dvcmQ=" (get-in r [:headers "authorization"]))))))

(deftest test-wrap-oauth
  (testing "with no oauth-token option"
    (is (not (contains? ((wrap-oauth identity) {}) :headers))))

  (testing "with oauth-token string option"
    (let [r ((wrap-oauth identity) {:oauth-token "some-token"})]
      (is (not (contains? r :basic-auth)))
      (is (str/starts-with? (get-in r [:headers "authorization"]) "Bearer")))))

(deftest test-wrap-user-info
  (testing "with no user-info option"
    (is (not (contains? ((wrap-user-info identity) {}) :basic-auth))))

  (testing "with oauth-token string option"
    (let [r ((wrap-user-info identity) {:user-info "user:pass"})]
      (is (= {:user "user" :pass "pass"} (:basic-auth r))))))

(deftest test-wrap-url
  (testing "with no url option"
    (is (not (contains? ((wrap-url identity) {}) :uri))))

  (testing "with basic url"
    (let [r ((wrap-url identity) {:url "http://google.com"})]
      (is (= {:query-string nil
              :scheme       :http
              :server-name  "google.com"
              :server-port  nil
              :uri          ""
              :url          "http://google.com"
              :user-info    nil} r))))

  (testing "with url with options"
    (let [r ((wrap-url identity) {:url "http://user:pass@host.com:1234/some/resource?a=b"})]
      (is (= {:query-string "a=b"
              :scheme       :http
              :server-name  "host.com"
              :server-port  1234
              :uri          "/some/resource"
              :url          "http://user:pass@host.com:1234/some/resource?a=b"
              :user-info    "user:pass"} r))))

  (testing "url encodes query"
    (let [r ((wrap-url identity) {:url "http://moo.com/some/resource?yo ho<"})]
      (is (= "yo%20ho%3C" (:query-string r))))))

(defn- gzip
  [bs]
  (with-open [out (ByteArrayOutputStream.)
              gzip (GZIPOutputStream. out)]
    (do
      (.write ^GZIPOutputStream gzip #^bytes bs)
      (.finish gzip)
      (.toByteArray out))))

(defn- string->stream
  [s]
  (clojure.java.io/input-stream (.getBytes ^String s)))

(deftest test-wrap-decompression
  (testing "with no decompress-body option"
    (let [r ((wrap-decompression identity) {})]
      (is (= "gzip, deflate" (get-in r [:headers "accept-encoding"])) "Adds request headers"))

    (are [response] (= "s" (-> ((wrap-decompression (constantly response)) {}) :body slurp))
                    {:body (string->stream "s")}
                    {:body (clojure.java.io/input-stream (gzip (.getBytes "s"))) :headers {"content-encoding" "gzip"}}
                    {:body (string->stream "s")}
                    {:body (clojure.java.io/input-stream (gzip (.getBytes "s"))) :headers {"content-encoding" "GZip"}}
                    ; TODO deflate
                    ))

  (testing "with decompress-body option"
    (let [r ((wrap-decompression identity) {:decompress-body false})]
      (is (not (contains? r :headers))))))


(deftest test-wrap-output-coercion
  (testing "coerces depending on status and :coerce option"
    (are [expected status coerce]
      (= expected (->
                    ((wrap-output-coercion (constantly {:status status :headers {"Content-Type" "application/json"} :body (string->stream "{\"a\": 1}")}))
                     {:coerce coerce}) :body))
      {:a 1} 200 nil
      {:a 1} 300 nil
      "{\"a\": 1}" 400 nil
      "{\"a\": 1}" 500 nil
      {:a 1} 200 :unexceptional
      {:a 1} 300 :unexceptional
      "{\"a\": 1}" 400 :unexceptional
      "{\"a\": 1}" 500 :unexceptional
      {:a 1} 200 :always
      {:a 1} 300 :always
      {:a 1} 400 :always
      {:a 1} 500 :always
      "{\"a\": 1}" 200 :exceptional
      "{\"a\": 1}" 300 :exceptional
      {:a 1} 400 :exceptional
      {:a 1} 500 :exceptional))


  (testing "auto performs content-type based decoding"
    (are [input type] (= {:a [1 2]} (-> ((wrap-output-coercion (constantly {:headers {"Content-Type" type} :body (string->stream input)})) {:as :auto}) :body))
                      "{\"a\": [1, 2]}" "application/json"
                      "{:a [1 2]}" "application/edn"
                      "[\"^ \",\"~:a\",[1,2]]" "application/transit+json"))

  (testing "auto turns text/* into strings"
    (are [input type] (= input (-> ((wrap-output-coercion (constantly {:headers {"Content-Type" type} :body (string->stream input)})) {:as :auto}) :body))
                      "<html>Hello</html>" "text/html"
                      "hello,world" "text/csv"))

  (testing "leaves bodies without content-type alone"
    (let [body (string->stream "hello")]
      (is (= body (-> ((wrap-output-coercion (constantly {:body body})) {:as :auto}) :body)))))

  (testing "clojure coercions"
    (is (= {:a 1} (-> ((wrap-output-coercion (constantly {:status 200 :body (string->stream "{:a 1}")})) {:as :clojure}) :body))))


  (testing "string coercions"
    (is (= "{:a 1}" (-> ((wrap-output-coercion (constantly {:status 200 :body (string->stream "{:a 1}")})) {:as :string}) :body))))

  (testing "byte-array coercions"
    (let [bs (string->stream "{:a 1}")]
      (is (= (Class/forName "[B")
             (let [#^bytes body (-> ((wrap-output-coercion (constantly {:status 200 :body bs})) {:as :byte-array}) :body)]
               (.getClass body))))
      (is (= bs (-> ((wrap-output-coercion (constantly {:status 200 :body bs})) {:as :stream}) :body))))))

(deftest test-wrap-exceptions
  (testing "for unexceptional status codes"
    (are [status] (= {:status status} ((wrap-exceptions (constantly {:status status})) {}))
                  200
                  300))

  (testing "with no throw-exceptions option"
    (are [status] (thrown? Exception ((wrap-exceptions (constantly {:status status})) {}))
                  400
                  500))

  (testing "with throw-exceptions option"
    (are [status] (= {:status status} ((wrap-exceptions (constantly {:status status})) {:throw-exceptions false}))
                  400
                  500)))

(deftest test-wrap-accept
  (testing "when no accept option"
    (let [r ((wrap-accept identity) {})]
      (is (not (contains? r :headers)))))

  (testing "with accept option"
    (are [expected accept] (= {:headers {"accept" expected}} ((wrap-accept identity) {:accept accept}))
                           "application/json" :json
                           "application/text" :text
                           "application/any-random-thing" :any-random-thing
                           "text/html" "text/html")))

(deftest test-wrap-accept-encoding
  (testing "when no accept-encoding option"
    (let [r ((wrap-accept-encoding identity) {})]
      (is (not (contains? r :headers)))))

  (testing "with accept-encoding option"
    (are [expected accept] (= {:headers {"accept-encoding" expected}} ((wrap-accept-encoding identity) {:accept-encoding accept}))
                           "gzip" [:gzip]
                           "gzip, deflate" ["gzip" "deflate"])))

(deftest test-wrap-content-type
  (testing "when no content-type option"
    (let [r ((wrap-content-type identity) {})]
      (is (not (contains? r :headers)))))

  (testing "with content-type option"
    (are [expected content-type] (= {:headers      {"Content-Type" expected}
                                     :content-type content-type} ((wrap-content-type identity) {:content-type content-type}))
                                 "application/json" :json
                                 "application/text" :text
                                 "application/any-random-thing" :any-random-thing
                                 "text/html" "text/html")))

(deftest test-wrap-form-params
  (testing "when no form-params option"
    (let [r ((wrap-form-params identity) {})]
      (is (not (contains? r :body)))))

  (testing "with default content-type"
    (let [r ((wrap-content-type (wrap-form-params identity)) {:form-params {:moo "cow boy!"} :request-method :post})]
      (is (= {:body           "moo=cow+boy%21"
              :content-type   "application/x-www-form-urlencoded"
              :request-method :post} r))))

  (testing "coercing to json"
    (let [r ((wrap-content-type (wrap-form-params identity)) {:form-params {:moo "cow boy!"} :request-method :post :content-type :json})
          r (update r :body slurp)
          ]
      (is (= {:body           "{\"moo\":\"cow boy!\"}"
              :content-type   "application/json"
              :headers        {"Content-Type" "application/json"}
              :request-method :post} r))))

  (testing "coercing to edn"
    (let [r ((wrap-content-type (wrap-form-params identity)) {:form-params {:moo "cow boy!"} :request-method :post :content-type :edn})
          r (update r :body slurp)
          ]
      (is (= {:body           "{:moo \"cow boy!\"}"
              :content-type   "application/edn"
              :headers        {"Content-Type" "application/edn"}
              :request-method :post} r))))

  (testing "coercing to transit+json"
    (let [r ((wrap-content-type (wrap-form-params identity)) {:form-params {:moo "cow boy!"} :request-method :post :content-type :transit+json})]
      (is (= "[\"^ \",\"~:moo\",\"cow boy!\"]" (slurp (:body r)))))))

(deftest test-wrap-method
  (testing "when no method option"
    (let [r ((wrap-method identity) {})]
      (is (not (contains? r :request-method)))))

  (testing "with method option"
    (are [method] (= {:request-method method} ((wrap-method identity) {:method method}))
                  :get
                  :post
                  :any-random-thing)))

(deftest test-wrap-request
  (testing "returns a response after passing through all the default middleware"
    (let [r ((wrap-request (constantly {:body (.getBytes "s") :status 200})) {:as :string})]
      (is (= "s" (:body r)))
      (is (number? (:request-time r)))))

  (testing "can supply custom middleware"
    (let [some-middleware (fn [client] (fn [req] (let [r (client req)] (assoc r :body-length (count (:body r))))))
          client (constantly {:body (.getBytes "s") :status 200})
          resp ((wrap-request client (conj default-middleware some-middleware)) {:as :string})]
      (is (= "s" (:body resp)))
      (is (= 1 (:body-length resp))))))

(deftest test-multipart
  (testing "without multipart option"
    (let [r ((wrap-multipart identity) {})]
      (is (nil? (:body r)))
      (is (empty? (:headers r)))))

  (testing "with multipart option"
    (let [r ((wrap-multipart identity) {:multipart [{:name "title" :content "My Awesome Picture"}]})]
      (is (instance? InputStream (:body r)))
      (is (re-matches #"^multipart/form-data; boundary=[a-zA-Z0-9_]+$" (-> r :headers (get "content-type"))))
      (is (nil? (:multipart r))))))


(deftest test-muuntaja
  (testing "default implementation"
    (let [r ((wrap-muuntaja identity) {})]
      (is (not (nil? (:muuntaja r))))
      (is (satisfies? m/Muuntaja (:muuntaja r))))))
