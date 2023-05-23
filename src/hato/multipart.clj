(ns hato.multipart
  "Core implementation of an HTTP client wrapping JDK11's java.net.http.HttpClient."
  (:refer-clojure :exclude [get])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s])
  (:import (java.io File InputStream SequenceInputStream)
           (java.net Socket URI URL)
           (java.nio.charset Charset StandardCharsets)
           (java.nio.file Files Path)
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

(def ^:private charset-pattern #".*(?i)charset\b=\s*\"?([^\";]+)\"?.*")

(defn- charset-from-content-type
  "Parses the charset from a content-type string. Examples:
  text/html;charset=utf-8
  text/html;charset=UTF-8
  Text/HTML;Charset=\"utf-8\"
  text/html; charset=\"utf-8\"

  See https://www.rfc-editor.org/rfc/rfc7231"
  [content-type]
  (second (re-matches charset-pattern content-type)))

(defn ^Charset charset-encoding
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

(defprotocol MultipartParam
  (input-stream [content opts])
  (length [content opts]))

(extend-protocol MultipartParam
  String
  (input-stream [^String content opts]
    (io/input-stream (.getBytes content (charset-encoding opts))))
  (length [^String content opts]
    (count (.getBytes content (charset-encoding opts))))

  File
  (input-stream [^File content _opts]
    (io/input-stream content))
  (length [^File content _opts]
    (.length content))

  Path
  (input-stream [^Path content _opts]
    (io/input-stream (.toFile content)))
  (length [^Path content opts]
    (length (.toFile content) opts))

  InputStream
  (input-stream [^InputStream content _opts]
    content)
  (length [^InputStream _content opts]
    (or (:content-length opts) -1))

  URL
  (input-stream [^URL content _opts]
    (io/input-stream content))
  (length [^URL content opts]
    (if (= "file" (.getProtocol content))
      (length (io/file content) opts)
      (or (:content-length opts) -1)))

  URI
  (input-stream [^URI content _opts]
    (io/input-stream content))
  (length [^URI content opts]
    (length (.toURL content) opts))

  Socket
  (input-stream [^InputStream content _opts]
    (io/input-stream content))
  (length [^InputStream _content opts]
    (or (:content-length opts) -1)))

;; See https://clojure.atlassian.net/browse/CLJ-1381 for why these are defined separately

(extend-protocol MultipartParam
  (Class/forName "[B")
  (input-stream [^bytes content _opts]
    (io/input-stream content))
  (length [^bytes content _opts]
    (count content)))

(defn raw-multipart-payload-segments
  "Given a collection of multipart parts, return a collection of tuples containing a segment of data
  representing the multipart body. Each tuple is the segment's content and options for the specific
  part (if applicable). These individual segments may be used to compute the content length or construct
  an InputStream.

  By default, a part's :content type must extend the MultipartParam protocol above!"
  [parts boundary]
  (let [payload-end-signal (.getBytes (str "--" boundary "--" line-break) StandardCharsets/UTF_8)]
    (concat
     (for [part        parts
           raw-segment [[(.getBytes (str "--"
                                         boundary
                                         line-break
                                         (content-disposition part)
                                         line-break
                                         (content-type part)
                                         line-break
                                         (content-transfer-encoding part)
                                         line-break
                                         line-break)
                                    StandardCharsets/UTF_8) nil]
                        [(:content part) (dissoc part :content)]
                        [(.getBytes line-break StandardCharsets/UTF_8) nil]]]
       raw-segment)
     [[payload-end-signal nil]])))

(defn body
  "Returns an InputStream from the supplied multipart parts. See raw-multipart-payload-segments
  for more information.

  Output looks something like:

  --hatoBoundary....\r
  Content-Disposition: form-data; name=\"title\"\r
  Content-Type: text/plain; charset=UTF-8\r
  Content-Transfer-Encoding: 8bit\r
  \r
  Some Content\r
  --hatoBoundary....\r
  ...more components
  --hatoBoundary....--\r"
  ([raw-multipart-payload-segments]
   (->> raw-multipart-payload-segments
        (mapv (fn [[content opts]]
                (input-stream content opts)))
        Collections/enumeration
        (SequenceInputStream.)))
  ([ms b]
   (body (raw-multipart-payload-segments ms b))))

(defn content-length
  "Returns the content length from the supplied multipart parts. If any of the parts return a
  content length of -1, return -1 indicating that we can't determine the appropriate size of
  the multipart body. See raw-multipart-payload-segments for more information."
  ([raw-multipart-payload-segments]
   (reduce
    (fn [acc [content opts]]
      (let [len (length content opts)]
        (if (= -1 len)
          (reduced -1)
          (+ acc len))))
    0
    raw-multipart-payload-segments))
  ([ms b]
   (content-length (raw-multipart-payload-segments ms b))))

(comment
 (def b (boundary))
 (def ms [{:name "title" :content "My Awesome Picture"}
          {:name "Content/type" :content "image/jpeg"}
          {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
          {:name "file" :content (io/file ".nrepl-port")}])

 ; Create the body
 (body ms b)

 ; Copy to out for testing
 (with-open [xin  (io/input-stream *1)
             xout (java.io.ByteArrayOutputStream.)]
   (io/copy xin xout)
   (.toByteArray xout))

 ; Print as string
 (String. *1))