(ns hato.conversion-test
  (:require [clojure.test :refer :all]
            [hato.conversion :refer :all]
            hato.middleware)
  (:import java.io.ByteArrayInputStream
           org.msgpack.MessagePack))

(deftest test-decode-transit
  (testing "with simple transit+json body"
    (let [r {:body (ByteArrayInputStream. (hato.middleware/transit-encode {:a "b"} :json))
             :content-type :application/transit+json}]
      (is (= {:a "b"} (decode r nil)))))

  (testing "with transit+json content-type but non-json body"
    (let [r {:body (ByteArrayInputStream. (.getBytes "abc"))
             :content-type :application/transit+json}]
      (is (thrown-with-msg? RuntimeException #"Unrecognized token" (decode r nil)))))

  (testing "with empty body"
    (let [r {:body (ByteArrayInputStream. (.getBytes ""))
             :content-type :application/transit+json}]
      (is (nil? (decode r nil))))))
