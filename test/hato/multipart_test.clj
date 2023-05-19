(ns hato.multipart-test
  (:require [clojure.test :refer :all]
            [hato.multipart :refer :all :as multipart]
            [clojure.spec.alpha :as s]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)))

(deftest test-boundary
  (let [b (boundary)]
    (is (nil? (s/explain-data ::multipart/boundary b)))))

(deftest test-body
  (let [_ (spit (io/file ".test-data") "[\"hello world\"]")
        ms [{:name "title" :content "My Awesome Picture"}
            {:name "diffCs" :content "custom cs" :content-type "text/plain; charset=\"iso-8859-1\""}
            {:name "expandedCs" :content "expanded cs" :content-type {:mime-type "text/plain" :charset StandardCharsets/US_ASCII}}
            {:name "expandedCsStr" :content "expanded cs str" :content-type {:mime-type "text/plain" :charset "utf-8"}}
            {:name "Content/type" :content "image/jpeg"}
            {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
            {:name "file" :content (io/file ".test-data")}
            {:name "is" :content (ByteArrayInputStream. (.getBytes ".test-data" "UTF-8")) :file-name "data.info"}
            {:name "data" :content (.getBytes "hi" "UTF-8") :content-type "text/plain" :file-name "data.txt"}
            {:name "jsonParam" :content (io/file ".test-data") :content-type "application/json" :file-name "data.json"}]
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

(comment
  (run-tests))