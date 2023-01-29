(ns hato.optional.cheshire
  (:require [cheshire.core :as cheshire]
            [hato.middleware :refer [decode-json encode-json]]))

(defmethod decode-json 'cheshire [[_ & args]]
  (apply cheshire/parse-stream-strict args))

(defmethod encode-json 'cheshire [[_ & args]]
  (apply cheshire/encode args))

(alter-var-root #'hato.middleware/*json-lib* (constantly 'cheshire))
