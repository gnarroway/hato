(ns hato.conversion
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io BufferedReader InputStream OutputStream)
           (java.util.stream Stream)))

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
  (fn [resp _] (:content-type resp)))

(defmethod decode :default
  [{:keys [content-type] :as resp} _]
  ; Throw for types that we would support if dependencies existed.
  (when (#{:application/json :application/transit+json :application/transit+msgpack} content-type)
    (throw (IllegalArgumentException.
            (format "Unable to decode content-type %s. Add optional dependencies or provide alternative decoder."
                    (:content-type resp)))))

  ; Return strings for text, or the original result otherwise.
  (if (= "text" (and content-type (namespace content-type)))
    (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
      (slurp (:body resp) :encoding charset))
    (:body resp)))

(defmethod decode :application/edn
  [resp _]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
    (edn/read-string (slurp (:body resp) :encoding charset))))

(when json-enabled?
  (let [decode-fn (ns-resolve 'cheshire.core 'decode-strict)]
    (defmethod decode :application/json
      [resp _]
      (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
        (decode-fn (slurp (:body resp) :encoding charset) true)))))

(when transit-enabled?
  (let [reader (ns-resolve 'cognitect.transit 'reader)
        read (ns-resolve 'cognitect.transit 'read)
        parse-transit (fn [type resp opts]
                        (with-open [^InputStream bs (:body resp)]
                          (try
                            (read (reader bs type (-> opts :transit-opts :decode)))
                            (catch RuntimeException e
                              ;; https://github.com/gnarroway/hato/issues/25
                              ;; explicitly handle case where stream is empty
                              ;; since .available seems to always return 0 on JDK11 (but not 15).
                              (if (not= java.io.EOFException (class (.getCause e)))
                                (throw e)
                                nil)))))]

    (defmethod decode :application/transit+json [resp opts]
      (parse-transit :json resp opts))

    (defmethod decode :application/transit+msgpack [resp opts]
      (parse-transit :msgpack resp opts))))

(defn input-stream-lines
  "Returns a Stream<String> lazily read by `(io/reader in)`.
   The returned Stream, must be closed when done with it."
  ^Stream [^InputStream in charset]
  (let [^BufferedReader rdr (io/reader in :encoding charset)]
    (-> (.lines rdr)
        (.onClose ^Runnable #(.close rdr)))))

(defmethod decode :text/event-stream ;; i.e. server-sent-events
  [resp _]
  (let [charset (or (-> resp :content-type-params :charset) "UTF-8")]
    (-> (:body resp)
        (input-stream-lines charset))))

(defrecord DefaultDecoder [options]
  Decoder
  (-decode [_ response] (decode response options)))

(comment
  (def d (DefaultDecoder. {}))
  (-decode d {:content-type :text/plain :body (io/input-stream (.getBytes "hello world"))})
  (-decode d {:content-type :application/foo :body (io/input-stream (.getBytes "{:yo 3}"))})
  (-decode d {:content-type :application/edn :body (io/input-stream (.getBytes "{:yo 3}"))})
  (-decode d {:content-type :application/json :body (io/input-stream (.getBytes "{\"hello\": 2}"))})
  (-decode d {:content-type :application/transit+json :body (io/input-stream (.getBytes "[\"^ \",\"~:a\",[1,2]]"))})
  ;; simulate event-lines arriving in a streaming fashion
  (let [in (ring.util.io/piped-input-stream
            (fn [^OutputStream out]
              (dotimes [i 5]
                (Thread/sleep (long (rand-int 2000)))
                (.write out (.getBytes (str "{:foo " i \} \newline))))))]
    (with-open [^Stream linez (-decode d {:content-type :text/event-stream :body in})]
      (->> (reify java.util.function.Consumer
             (accept [_ line]
               (println (java.time.LocalDateTime/now) "=>" line)))
           (.forEach linez))))
  )
