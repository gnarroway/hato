(ns hato.multipart
  "Core implementation of an HTTP client wrapping JDK11's java.net.http.HttpClient."
  (:refer-clojure :exclude [get])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s])
  (:import (java.io PipedOutputStream PipedInputStream File)
           (java.nio.file Files)))

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
         content-type content-type
         (string? content) "text/plain; charset=UTF-8"
         (instance? File content) (or (Files/probeContentType (.toPath ^File content))
                                      "application/octet-stream")
         :else "application/octet-stream")))

(defn- content-transfer-encoding
  [{:keys [content]}]
  (if (string? content)
    "Content-Transfer-Encoding: 8bit"
    "Content-Transfer-Encoding: binary"))

(def ^:private line-break (.getBytes "\r\n"))


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

(defn body
  "Returns an InputStream from the multipart inputs.

  This is achieved by writing all the inputs to an output stream which is piped
  back into an InputStream, hopefully to avoid making a copy of everything (which could
  be the case if we read all the bytes and used a ByteArrayOutputStream instead).

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
  (let [in-stream (PipedInputStream.)
        out-stream (PipedOutputStream. in-stream)]
    (.start (Thread. #(do (doseq [m ms
                                  s [(str "--" b)
                                     line-break
                                     (content-disposition m)
                                     line-break
                                     (content-type m)
                                     line-break
                                     (content-transfer-encoding m)
                                     line-break
                                     line-break
                                     (:content m)
                                     line-break]]
                            (io/copy s out-stream))
                          (io/copy (str "--" b "--") out-stream)
                          (io/copy line-break out-stream)
                          (.close out-stream))))
    in-stream))

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