(ns hato.multipart
  "Core implementation of an HTTP client wrapping JDK11's java.net.http.HttpClient."
  (:refer-clojure :exclude [get])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s])
  (:import (java.io ByteArrayInputStream File SequenceInputStream)
           (java.nio.charset Charset StandardCharsets)
           (java.nio.file Files)
           (java.util Collections)))

;;; Helpers

(defn- content-disposition
  [{:keys [part-name name content file-name]}]
  (str "Content-Disposition: form-data; "
       (format "name=\"%s\"" (or part-name name))
       (when-let [fname (or file-name
                            (when (instance? File content)
                              (.getName ^File content)))]
         (format "; filename=\"%s\"" fname))))

(defn- content-type
  [{:keys [content content-type]}]
  (str "Content-Type: "
       (cond
         content-type (if (string? content-type)
                        content-type
                        (str (:mime-type content-type)
                             (when-let [charset (:charset content-type)]
                               (str "; charset=" charset))))
         (string? content) "text/plain; charset=UTF-8"
         (instance? File content) (or (Files/probeContentType (.toPath ^File content))
                                      "application/octet-stream")
         :else "application/octet-stream")))

(defn- content-transfer-encoding
  [{:keys [content]}]
  (if (string? content)
    "Content-Transfer-Encoding: 8bit"
    "Content-Transfer-Encoding: binary"))

(defn- charset-from-content-type
  "Parses the charset from a content-type string. Examples:
  text/html;charset=utf-8
  text/html;charset=UTF-8
  Text/HTML;Charset=\"utf-8\"
  text/html; charset=\"utf-8\"

  See https://www.rfc-editor.org/rfc/rfc7231"
  [content-type]
  (second (re-matches #".*charset=\s*\"?([^\";]+)\"?.*" content-type)))

(defn- ^Charset charset-encoding
  "Determines the appropriate charset to encode a string with given the supplied content-type."
  [{:keys [content-type]}]
  (as-> content-type $
        (if (string? $)
          (charset-from-content-type $)
          (:charset $))
        (or $ StandardCharsets/UTF_8)
        (if (instance? String $)
          (Charset/forName $)
          $)))

(def ^{:private true :tag String} line-break "\r\n")

;;; Exposed functions

; See page 21 of https://www.ietf.org/rfc/rfc2046.txt
; This is a more restrictive spec consisting of alphanumeric + underscore


(s/def ::boundary (s/and string?
                         #(<= 1 (count %) 70)
                         #(re-matches #"^[a-zA-Z0-9_]+$" %)))

(defn boundary
  "Creates a boundary string compliant with RFC2046

  See https://www.ietf.org/rfc/rfc2046.txt"
  []
  (->> (repeatedly #(rand-nth "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWQYZ_"))
       (take 30)
       (apply str "hatoBoundary")))

(defmulti multipart-content->input-stream
  (fn [content _opts]
    (type content)))

(defmethod multipart-content->input-stream String [^String content opts]
  (ByteArrayInputStream. (.getBytes content (charset-encoding opts))))

(defmethod multipart-content->input-stream :default [content _opts]
  (io/input-stream content))

(defn body
  "Returns an InputStream from the multipart inputs.

  This is achieved by combining all the inputs/parts into a new InputStream. Parts that
  are not a string should be coercible to an InputStream via clojure.java.io/input-stream
  or by extending `multipart-content->input-stream`. Ideally this input stream is lazy
  for parts with contents of a File/InputStream/URL/URI/Socket/etc.

  Output looks something like:

  --hatoBoundary....\r
  Content-Disposition: form-data; name=\"title\"\r
  Content-Type: text/plain; charset=UTF-8\r
  Content-Transfer-Encoding: 8bit\r
  \r
  Some Content\r
  --hatoBoundary....\r
  ...more components
  --hatoBoundary....--\r
  "
  [ms b]
  (SequenceInputStream.
   (Collections/enumeration
    (concat (for [m ms
                  s [(ByteArrayInputStream. (.getBytes (str "--"
                                                            b
                                                            line-break
                                                            (content-disposition m)
                                                            line-break
                                                            (content-type m)
                                                            line-break
                                                            (content-transfer-encoding m)
                                                            line-break
                                                            line-break)
                                                       StandardCharsets/UTF_8))
                     (multipart-content->input-stream (:content m) m)
                     (ByteArrayInputStream. (.getBytes line-break StandardCharsets/UTF_8))]]
              s)
            [(ByteArrayInputStream. (.getBytes (str "--" b "--" line-break) StandardCharsets/UTF_8))]))))

(comment
  (def b (boundary))
  (def ms [{:name "title" :content "My Awesome Picture"}
           {:name "Content/type" :content "image/jpeg"}
           {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
           {:name "file" :content (io/file ".nrepl-port")}])

  ; Create the body
  (body ms b)

  ; Copy to out for testing
  (with-open [xin (io/input-stream *1)
              xout (java.io.ByteArrayOutputStream.)]
    (io/copy xin xout)
    (.toByteArray xout))

  ; Print as string
  (String. *1))