(ns hato.conversion
  (:require [clojure.edn :as edn])
  (:import (java.io InputStream)))

(set! *warn-on-reflection* true)

;;; protocols

(defprotocol Decoder
  (-decode [decoder response]))

;;; json

(def json-enabled?
  (try
    (require 'cheshire.core)
    true
    (catch Throwable _ false)))

;;; transit

(def transit-enabled?
  (try
    (require 'cognitect.transit)
    true
    (catch Throwable _ false)))

;;; Decoding

(defmulti decode
  "Extensible content-type based decoder."
  :content-type)

(defmethod decode :default
  [{:keys [content-type] :as resp}]
  ; Throw for types that we would support if dependencies existed.
  (when (#{:application/json :application/transit+json :application/transit+msgpack} content-type)
    (throw (IllegalArgumentException.
            (format "Unable to decode content-type %s. Add optional dependencies or provide alternative decoder."
                    (:content-type resp)))))

  ; Return strings for text, or the original result otherwise.
  (if (= "text" (namespace content-type))
    (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
      (slurp (:body resp) :encoding charset))
    (:body resp)))

(defmethod decode :application/edn
  [resp]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
    (edn/read-string (slurp (:body resp) :encoding charset))))

(when json-enabled?
  (let [decode-fn (ns-resolve 'cheshire.core 'decode-strict)]
    (defmethod decode :application/json
      [resp]
      (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
        (decode-fn (slurp (:body resp) :encoding charset) true)))))

(when transit-enabled?
  (let [reader (ns-resolve 'cognitect.transit 'reader)
        read (ns-resolve 'cognitect.transit 'read)
        parse-transit (fn [type resp]
                        (with-open [^InputStream bs (:body resp)]
                          (when (pos? (.available bs))
                            (read (reader bs type)))))]

    (defmethod decode :application/transit+json [resp]
      (parse-transit :json resp))

    (defmethod decode :application/transit+msgpack [resp]
      (parse-transit :msgpack resp))))

(defrecord DefaultDecoder []
  Decoder
  (-decode [_ response] (decode response)))

(comment
  (def d (DefaultDecoder.))
  (-decode d {:content-type :text/plain :body (clojure.java.io/input-stream (.getBytes "hello world"))})
  (-decode d {:content-type :application/foo :body (clojure.java.io/input-stream (.getBytes "{:yo 3}"))})
  (-decode d {:content-type :application/edn :body (clojure.java.io/input-stream (.getBytes "{:yo 3}"))})
  (-decode d {:content-type :application/json :body (clojure.java.io/input-stream (.getBytes "{\"hello\": 2}"))})
  (-decode d {:content-type :application/transit+json :body (clojure.java.io/input-stream (.getBytes "[\"^ \",\"~:a\",[1,2]]"))}))