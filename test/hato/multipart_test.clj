(ns hato.multipart-test
  (:require [clojure.test :refer :all]
            [hato.multipart :refer :all :as multipart]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

(defn- make-test-segments
  []
  (spit (io/file ".test-data") "[\"hello world\"]")
  [{:name "title" :content "My Awesome Picture"}
   {:name "diffCs" :content "custom cs" :content-type "text/plain; charset=\"iso-8859-1\""}
   {:name "expandedCs" :content "expanded cs" :content-type {:mime-type "text/plain" :charset StandardCharsets/US_ASCII}}
   {:name "expandedCsStr" :content "expanded cs str" :content-type {:mime-type "text/plain" :charset "utf-8"}}
   {:name "Content/type" :content "image/jpeg"}
   {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
   {:name "uri" :content (.toURI (io/file ".test-data"))}
   {:name "url" :content (.toURL (.toURI (io/file ".test-data")))}
   {:name "file" :content (io/file ".test-data")}
   {:name "is" :content (ByteArrayInputStream. (.getBytes ".test-data" "UTF-8")) :file-name "data.info" :content-length (count (.getBytes ".test-data" "UTF-8"))}
   {:name "data" :content (.getBytes "hi" "UTF-8") :content-type "text/plain" :file-name "data.txt"}
   {:name "jsonParam" :content (io/file ".test-data") :content-type "application/json" :file-name "data.json"}])

(deftest test-boundary
  (let [b (boundary)]
    (is (nil? (s/explain-data ::multipart/boundary b)))))

(deftest test-body
  (let [ms (make-test-segments)
        b (body ms "boundary")
        out-string (with-open [xin (io/input-stream b)
                               xout (ByteArrayOutputStream.)]
                     (io/copy xin xout)
                     (String. (.toByteArray xout)))]
    (is (=
         (str "--boundary\r\n"
              "Content-Disposition: form-data; name=\"title\"\r\n"
              "Content-Type: text/plain; charset=UTF-8\r\n"
              "Content-Transfer-Encoding: 8bit\r\n"
              "\r\n"
              "My Awesome Picture\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"diffCs\"\r\n"
              "Content-Type: text/plain; charset=\"iso-8859-1\"\r\n"
              "Content-Transfer-Encoding: 8bit\r\n"
              "\r\n"
              "custom cs\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"expandedCs\"\r\n"
              "Content-Type: text/plain; charset=US-ASCII\r\n"
              "Content-Transfer-Encoding: 8bit\r\n"
              "\r\n"
              "expanded cs\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"expandedCsStr\"\r\n"
              "Content-Type: text/plain; charset=utf-8\r\n"
              "Content-Transfer-Encoding: 8bit\r\n"
              "\r\n"
              "expanded cs str\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"Content/type\"\r\n"
              "Content-Type: text/plain; charset=UTF-8\r\n"
              "Content-Transfer-Encoding: 8bit\r\n"
              "\r\n"
              "image/jpeg\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"eggplant\"\r\n"
              "Content-Type: text/plain; charset=UTF-8\r\n"
              "Content-Transfer-Encoding: 8bit\r\n"
              "\r\n"
              "Eggplants\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"uri\"\r\n"
              "Content-Type: application/octet-stream\r\n"
              "Content-Transfer-Encoding: binary\r\n"
              "\r\n"
              "[\"hello world\"]\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"url\"\r\n"
              "Content-Type: application/octet-stream\r\n"
              "Content-Transfer-Encoding: binary\r\n"
              "\r\n"
              "[\"hello world\"]\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"file\"; filename=\".test-data\"\r\n"
              "Content-Type: application/octet-stream\r\n"
              "Content-Transfer-Encoding: binary\r\n"
              "\r\n"
              "[\"hello world\"]\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"is\"; filename=\"data.info\"\r\n"
              "Content-Type: application/octet-stream\r\n"
              "Content-Transfer-Encoding: binary\r\n"
              "\r\n"
              ".test-data\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"data\"; filename=\"data.txt\"\r\n"
              "Content-Type: text/plain\r\n"
              "Content-Transfer-Encoding: binary\r\n"
              "\r\n"
              "hi\r\n"

              "--boundary\r\n"
              "Content-Disposition: form-data; name=\"jsonParam\"; filename=\"data.json\"\r\n"
              "Content-Type: application/json\r\n"
              "Content-Transfer-Encoding: binary\r\n"
              "\r\n"
              "[\"hello world\"]\r\n"
              "--boundary--\r\n") out-string))))

(deftest test-content-length
  (testing "when all segment content lengths are known, the computed length matches the actual body length"
    (let [ms (make-test-segments)
          segments (raw-multipart-payload-segments ms "boundary")
          b (body segments)
          computed-length (content-length segments)
          actual-length (with-open [xin  (io/input-stream b)
                                    xout (ByteArrayOutputStream.)]
                          (io/copy xin xout)
                          (count (.toByteArray xout)))]
      (is (= computed-length actual-length))))
  (testing "when a single segment's length is unknown, computed length is -1"
    (let [ms (concat (make-test-segments)
                     [{:name "is" :content (ByteArrayInputStream. (.getBytes ".test-data" "UTF-8")) :file-name "data.info"}])
          segments (raw-multipart-payload-segments ms "boundary")
          b (body segments)
          computed-length (content-length segments)
          actual-length (with-open [xin  (io/input-stream b)
                                    xout (ByteArrayOutputStream.)]
                          (io/copy xin xout)
                          (count (.toByteArray xout)))]
      (is (not= computed-length actual-length))
      (is (= computed-length -1)))))

(comment
  (run-tests))