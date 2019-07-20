(ns hato.multipart-test
  (:require [clojure.test :refer :all]
            [hato.multipart :refer :all :as multipart]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayOutputStream)))

(deftest test-boundary
  (let [b (boundary)]
    (is (nil? (s/explain-data ::multipart/boundary b)))))

(deftest test-body
  (let [_ (spit (io/file ".test-data") "hello world")
        ms [{:name "title" :content "My Awesome Picture"}
            {:name "Content/type" :content "image/jpeg"}
            {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
            {:name "file" :content (io/file ".test-data")}]
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
              "Content-Disposition: form-data; name=\"file\"; filename=\".test-data\"\r\n"
              "Content-Type: application/octet-stream\r\n"
              "Content-Transfer-Encoding: binary\r\n"
              "\r\n"
              "hello world\r\n"
              "--boundary--\r\n") out-string))))

(comment
  (run-tests))