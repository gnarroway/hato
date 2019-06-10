(ns hato.client-test
  (:refer-clojure :exclude [get])
  (:require [clojure.test :refer :all]
            [hato.client :refer :all])
  (:import (java.io InputStream)))


(deftest ^:Integration test-basic-response
  (testing "basic get request"
    (let [r (get "https://httpbin.org/get" {:as :json})]
      (is (pos-int? (:request-time r)))
      (is (= 200 (:status r)))
      (is (= "https://httpbin.org/get" (:uri r)))
      (is (= :http-1.1 (:version r)))
      (is (= :get (-> r :request :request-method)))
      (is (= "gzip, deflate" (get-in r [:request :headers "accept-encoding"]))))))

(deftest ^:Integration test-exceptions
  (testing "throws on exceptional status"
    (is (thrown? Exception (get "https://httpbin.org/status/500" {}))))

  (testing "can opt out"
    (is (= 500 (:status (get "https://httpbin.org/status/500" {:throw-exceptions false}))))))

(deftest ^:Integration test-coercions
  (testing "as default"
    (let [r (get "https://httpbin.org/get" {})]
      (is (string?  (:body r)))))

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

(deftest ^:Integration test-auth
  (testing "basic auth"
    (let [r (get "https://httpbin.org/basic-auth/user/pass" {:basic-auth "user:pass"})]
      (is (= 200 (:status r))))

    (is (thrown? Exception (get "https://httpbin.org/basic-auth/user/pass" {:basic-auth "invalid:pass"})))))

(deftest ^:Integration test-redirects
  (let [redirect-to "https://httpbin.org/get"
        uri (format "https://httpbin.org/redirect-to?url=%s" redirect-to)]
    (testing "no redirects (default)"
      (let [r (get uri {:as :string})]
        (is (= 302 (:status r)))
        (is (= uri (:uri r)))))

    (testing "explicitly never"
      (let [r (get uri {:as :string :follow-redirects :never})]
        (is (= 302 (:status r)))
        (is (= uri (:uri r)))))

    (testing "always redirect"
      (let [r (get uri {:as :string :follow-redirects :always})]
        (is (= 200 (:status r)))
        (is (= redirect-to (:uri r)))))

    (testing "normal redirect (same protocol)"
      (let [r (get uri {:as :string :follow-redirects :normal})]
        (is (= 200 (:status r)))
        (is (= redirect-to (:uri r)))))

    (testing "normal redirect (not same protocol)"
      (let [https-tp-http-uri (format "https://httpbin.org/redirect-to?url=%s" "http://httpbin.org/get")
            r (get https-tp-http-uri {:as :string :follow-redirects :normal})]
        (is (= 302 (:status r)))
        (is (= https-tp-http-uri (:uri r)))))))

(deftest ^:Integration test-cookies
  (testing "no cookie manager"
    (let [r (get "https://httpbin.org/cookies/set/moo/cow" {:as :json :follow-redirects :always})]
      (is (= 200 (:status r)))
      (is (nil? (-> r :body :cookies :moo)))))

  (testing "no cookie manager"
    (let [r (get "https://httpbin.org/cookies/set/moo/cow" {:as :json :follow-redirects :always :cookie-policy :all})]
      (is (= 200 (:status r)))
      (is (= "cow" (-> r :body :cookies :moo)))))

  (testing "persists over requests"
    (let [c (build-http-client {:follow-redirects :always :cookie-policy :all})
          _ (get "https://httpbin.org/cookies/set/moo/cow" {:http-client c})
          r (get "https://httpbin.org/cookies" {:as :json :http-client c})]
      (is (= 200 (:status r)))
      (is (= "cow" (-> r :body :cookies :moo))))))

(deftest ^:Integration test-decompression
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

