(ns hato.format.text
  (:refer-clojure :exclude [format])
  (:require [clojure.edn :as edn]
            [muuntaja.format.core :as core])
  (:import (java.io InputStreamReader PushbackReader InputStream OutputStream)))

(defn decoder [options]
  (reify
    core/Decode
    (decode [_ data charset]
      (slurp (InputStreamReader. ^InputStream data ^String charset)))))

(defn encoder [_]
  (reify
    core/EncodeToBytes
    (encode-to-bytes [_ data charset]
      (.getBytes
        (str data)
        ^String charset))
    core/EncodeToOutputStream
    (encode-to-output-stream [_ data charset]
      (fn [^OutputStream output-stream]
        (.write output-stream (.getBytes
                                (str data)
                                ^String charset))))))

(def generic
  (core/map->Format
    {:name "text/*"
     :matches #"^text/(.+)$"
     :decoder [decoder]
     :encoder [encoder]}))
