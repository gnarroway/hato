(ns hato.optional.data-json
  (:require [clojure.data.json :as json]
            [hato.middleware :refer [decode-json encode-json]]))

(defmethod decode-json 'clojure.data.json [[_ reader key-fn & args]]
  (apply json/read reader
                   :key-fn (if (true? key-fn)
                             keyword
                             identity)
                   args))

(defmethod encode-json 'clojure.data.json [[_ & args]]
  ;; Mimicks the behavior of cheshire.core/encode
  (apply json/write-str args))

(alter-var-root #'hato.middleware/*json-lib* (constantly 'clojure.data.json))
