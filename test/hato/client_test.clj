(ns hato.client-test
  (:refer-clojure :exclude [get])
  (:require [clojure.test :refer :all]
            [hato.client :refer :all]
            [clojure.java.io :as io])
  (:import (java.io InputStream)
           (java.net ProxySelector CookieHandler Authenticator)
           (java.net.http HttpClient$Redirect HttpClient$Version HttpClient)
           (java.time Duration)
           (javax.net.ssl SSLContext)))

(deftest test-build-http-client
  (testing "authenticator"
    (is (.isEmpty (.authenticator (build-http-client {}))) "not set by default")
    (is (= "user" (-> (build-http-client {:authenticator {:user "user" :pass "pass"}}) (.authenticator) ^Authenticator (.get) (.getPasswordAuthentication) (.getUserName))))
    (is (.isEmpty (.authenticator (build-http-client {:authenticator :some-invalid-value}))) "ignore invalid input"))

  (testing "connect-timeout"
    (is (.isEmpty (.connectTimeout (build-http-client {}))) "not set by default")
    (is (= 5 (-> (build-http-client {:connect-timeout 5}) (.connectTimeout) ^Duration (.get) (.toMillis))))
    (is (thrown? Exception (build-http-client {:connect-timeout :not-a-number}))))

  (testing "cookie-manager and cookie-policy"
    (is (.isEmpty (.cookieHandler (build-http-client {}))) "not set by default")
    (are [x] (instance? CookieHandler (-> ^HttpClient (build-http-client {:cookie-policy x}) (.cookieHandler) (.get)))
             :none
             :all
             :original-server
             :any-random-thing                              ; Invalid values are ignored, so the default :original-server will be in effect
             )

    (let [cm (cookie-manager :none)]
      (is (= cm (-> (build-http-client {:cookie-handler cm :cookie-policy :all}) (.cookieHandler) (.get)))
          ":cookie-handler takes precedence over :cookie-policy")))

  (testing "redirect-policy"
    (is (= HttpClient$Redirect/NEVER (.followRedirects (build-http-client {}))) "NEVER by default")
    (are [expected option] (= expected (.followRedirects (build-http-client {:redirect-policy option})))
                           HttpClient$Redirect/ALWAYS :always
                           HttpClient$Redirect/NEVER :never
                           HttpClient$Redirect/NORMAL :normal)
    (is (thrown? Exception (build-http-client {:redirect-policy :not-valid-value}))))

  (testing "priority"
    (is (build-http-client {:priority 1}))
    (is (build-http-client {:priority 256}))
    (is (thrown? Exception (build-http-client {:priority :not-a-number})))
    (are [x] (thrown? Exception (build-http-client {:priority x}))
             :not-a-number
             0
             257))

  (testing "proxy"
    (is (.isEmpty (.proxy (build-http-client {}))) "not set by default")
    (is (.isPresent (.proxy (build-http-client {:proxy :no-proxy}))))
    (is (.isPresent (.proxy (build-http-client {:proxy (ProxySelector/getDefault)})))))

  (testing "ssl-context"
    (is (= (SSLContext/getDefault) (.sslContext (build-http-client {}))))
    (is (not= (SSLContext/getDefault) (.sslContext (build-http-client {:ssl-context {:keystore         (io/resource "keystore.p12")
                                                                                     :keystore-pass    "borisman"
                                                                                     :trust-store      (io/resource "keystore.p12")
                                                                                     :trust-store-pass "borisman"}})))))

  (testing "version"
    (is (= HttpClient$Version/HTTP_2 (.version (build-http-client {}))) "HTTP_2 by default")
    (are [expected option] (= expected (.version (build-http-client {:version option})))
                           HttpClient$Version/HTTP_1_1 :http-1.1
                           HttpClient$Version/HTTP_2 :http-2)
    (is (thrown? Exception (build-http-client {:version :not-valid-value})))))

(deftest ^:integration test-basic-response
  (testing "basic get request"
    (let [r (get "https://httpbin.org/get" {:as :json})]
      (is (pos-int? (:request-time r)))
      (is (= 200 (:status r)))
      (is (= "https://httpbin.org/get" (:uri r)))
      (is (= :http-1.1 (:version r)))
      (is (= :get (-> r :request :request-method)))
      (is (= "gzip, deflate" (get-in r [:request :headers "accept-encoding"]))))))

(deftest ^:integration test-exceptions
  (testing "throws on exceptional status"
    (is (thrown? Exception (get "https://httpbin.org/status/500" {}))))

  (testing "can opt out"
    (is (= 500 (:status (get "https://httpbin.org/status/500" {:throw-exceptions false}))))))

(deftest ^:integration test-coercions
  (testing "as default"
    (let [r (get "https://httpbin.org/get" {})]
      (is (string? (:body r)))))

  (testing "as byte array"
    (let [r (get "https://httpbin.org/get" {:as :byte-array})]
      (is (instance? (Class/forName "[B") (:body r)))
      (is (string? (String. (:body r))))))

  (testing "as stream"
    (let [r (get "https://httpbin.org/get" {:as :stream})]
      (is (instance? InputStream (:body r)))
      (is (string? (-> r :body slurp)))))

  (testing "as string"
    (let [r (get "https://httpbin.org/get" {:as :string})]
      (is (string? (:body r)))))

  (testing "as json"
    (let [r (get "https://httpbin.org/get" {:as :json})]
      (is (coll? (:body r))))))

(deftest ^:integration test-auth
  (testing "authenticator basic auth (non-preemptive)"
    (let [r (get "https://httpbin.org/basic-auth/user/pass" {:authenticator {:user "user" :pass "pass"}})]
      (is (= 200 (:status r))))

    (is (thrown? Exception (get "https://httpbin.org/basic-auth/user/pass" {:basic-auth "invalid:pass"}))))

  (testing "basic auth"
    (let [r (get "https://httpbin.org/basic-auth/user/pass" {:basic-auth "user:pass"})]
      (is (= 200 (:status r))))

    (is (thrown? Exception (get "https://httpbin.org/basic-auth/user/pass" {:basic-auth "invalid:pass"})))))

(deftest ^:integration test-redirects
  (let [redirect-to "https://httpbin.org/get"
        uri (format "https://httpbin.org/redirect-to?url=%s" redirect-to)]
    (testing "no redirects (default)"
      (let [r (get uri {:as :string})]
        (is (= 302 (:status r)))
        (is (= uri (:uri r)))))

    (testing "explicitly never"
      (let [r (get uri {:as :string :redirect-policy :never})]
        (is (= 302 (:status r)))
        (is (= uri (:uri r)))))

    (testing "always redirect"
      (let [r (get uri {:as :string :redirect-policy :always})]
        (is (= 200 (:status r)))
        (is (= redirect-to (:uri r)))))

    (testing "normal redirect (same protocol)"
      (let [r (get uri {:as :string :redirect-policy :normal})]
        (is (= 200 (:status r)))
        (is (= redirect-to (:uri r)))))

    (testing "normal redirect (not same protocol)"
      (let [https-tp-http-uri (format "https://httpbin.org/redirect-to?url=%s" "http://httpbin.org/get")
            r (get https-tp-http-uri {:as :string :redirect-policy :normal})]
        (is (= 302 (:status r)))
        (is (= https-tp-http-uri (:uri r)))))

    (testing "default max redirects"
      (are [status redirects] (= status (:status (get (str "https://httpbin.org/redirect/" redirects) {:redirect-policy :normal})))
                              200 4
                              302 5))))

(deftest ^:integration test-cookies
  (testing "no cookie manager"
    (let [r (get "https://httpbin.org/cookies/set/moo/cow" {:as :json :redirect-policy :always})]
      (is (= 200 (:status r)))
      (is (nil? (-> r :body :cookies :moo)))))

  (testing "no cookie manager"
    (let [r (get "https://httpbin.org/cookies/set/moo/cow" {:as :json :redirect-policy :always :cookie-policy :all})]
      (is (= 200 (:status r)))
      (is (= "cow" (-> r :body :cookies :moo)))))

  (testing "persists over requests"
    (let [c (build-http-client {:redirect-policy :always :cookie-policy :all})
          _ (get "https://httpbin.org/cookies/set/moo/cow" {:http-client c})
          r (get "https://httpbin.org/cookies" {:as :json :http-client c})]
      (is (= 200 (:status r)))
      (is (= "cow" (-> r :body :cookies :moo))))))

(deftest ^:integration test-decompression
  (testing "gzip via byte array"
    (let [r (get "https://httpbin.org/gzip" {:as :json})]
      (is (= 200 (:status r)))
      (is (true? (-> r :body :gzipped)))))

  (testing "gzip via stream"
    (let [r (get "https://httpbin.org/gzip" {:as :stream})]
      (is (= 200 (:status r)))
      (is (instance? InputStream (:body r)))))

  (testing "deflate via byte array"
    (let [r (get "https://httpbin.org/deflate" {:as :json})]
      (is (= 200 (:status r)))
      (is (true? (-> r :body :deflated)))))

  (testing "deflate via stream"
    (let [r (get "https://httpbin.org/deflate" {:as :stream})]
      (is (= 200 (:status r)))
      (is (instance? InputStream (:body r))))))

(comment
  (run-tests))