(ns hato.middleware
  "Adapted from https://www.github.com/dakrone/clj-http"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.walk :refer [prewalk]]
    [hato.multipart :as multipart]
    [hato.conversion :as conversion]
    [muuntaja.core :as m]
    [hato.format.text :as format.text])
  (:import
    (hato.conversion DefaultDecoder)
    (java.util
      Base64)
    (java.io
      InputStream
      ByteArrayOutputStream
      BufferedInputStream)
    (java.net
      URLDecoder
      URLEncoder
      URL)
    (java.util.zip
      GZIPInputStream InflaterInputStream ZipException Inflater)
    (java.net.http HttpRequest$BodyPublishers)))


;; Cheshire is an optional dependency, so we check for it at compile time.


(def json-enabled?
  (try
    (require
      'cheshire.core)
    true
    (catch Throwable _ false)))

;; Transit is an optional dependency, so check at compile time.
(def transit-enabled?
  (try
    (require
      'cognitect.transit)
    true
    (catch Throwable _ false)))

(defn ^:dynamic parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [^InputStream in type & [opts]]
  {:pre [transit-enabled?]}
  (let [reader (ns-resolve 'cognitect.transit 'reader)
        read (ns-resolve 'cognitect.transit 'read)]
    (try
      (read (reader in type (:decode opts)))
      (catch RuntimeException _
        ; https://github.com/gnarroway/hato/issues/25
        ; explicitly handle case where stream is empty
        ; since .available seems to always return 0 on JDK11 (but not 15).
        nil))))

(defn ^:dynamic transit-encode
  "Resolve and apply Transit's JSON/MessagePack encoding."
  [out type & [opts]]
  {:pre [transit-enabled?]}
  (let [output (ByteArrayOutputStream.)
        writer (ns-resolve 'cognitect.transit 'writer)
        write (ns-resolve 'cognitect.transit 'write)]
    (write (writer output type (:encode opts)) out)
    (.toByteArray output)))

(defn ^:dynamic json-encode
  "Resolve and apply cheshire's json encoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "encode")) args))

(defn ^:dynamic json-decode-stream-strict
  "Resolve and apply cheshire's json decoding dynamically (with lazy parsing disabled)."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "parse-stream-strict")) args))

;;;

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn opt
  "Checks the request parameters for a keyword boolean option, with or without the ?

  Returns false if either of the values are false, or the value of
  (or key1 key2) otherwise (truthy)"
  [req param]
  (let [param-? (keyword (str (name param) "?"))
        v1 (clojure.core/get req param)
        v2 (clojure.core/get req param-?)]
    (if (false? v1)
      false
      (if (false? v2)
        false
        (or v1 v2)))))

(defn url-encode
  "Returns a UTF-8 URL encoded version of the given string."
  [^String unencoded & [encoding]]
  (let [^String enc (or encoding "UTF-8")]
    (URLEncoder/encode unencoded enc)))


;;;


(defn url-encode-illegal-characters
  "Takes a raw url path or query and url-encodes any illegal characters.
  Minimizes ambiguity by encoding space to %20."
  [path-or-query]
  (when path-or-query
    (-> path-or-query
        (str/replace " " "%20")
        (str/replace
          #"[^a-zA-Z0-9\.\-\_\~\!\$\&\'\(\)\*\+\,\;\=\:\@\/\%\?]"
          url-encode))))

(defn parse-url
  "Parse a URL string into a map of interesting parts."
  [url]
  (let [url-parsed (URL. url)]
    {:scheme       (keyword (.getProtocol url-parsed))
     :server-name  (.getHost url-parsed)
     :server-port  (when-pos (.getPort url-parsed))
     :uri          (url-encode-illegal-characters (.getPath url-parsed))
     :url          url
     :user-info    (some-> (.getUserInfo url-parsed)
                           (URLDecoder/decode "UTF-8"))
     :query-string (url-encode-illegal-characters (.getQuery url-parsed))}))


;; Statuses for which hato will not throw an exception


(defn unexceptional-status? [status]
  (or (nil? status)
      (#{200 201 202 203 204 205 206 207 300 301 302 303 304 307} status)))

(defn- exceptions-response
  [req {:keys [status] :as resp}]
  (if (unexceptional-status? status)
    resp
    (cond
      (false? (opt req :throw-exceptions))
      resp

      :else
      (throw (ex-info (str "status: " status) resp)))))

(defn wrap-exceptions
  "Middleware that throws response as an ExceptionInfo if the response has
  unsuccessful status code. :throw-exceptions set to false in the request
  disables this middleware."
  [client]
  (fn
    ([req]
     (exceptions-response req (client req)))
    ([req response raise]
     (client req #(response (exceptions-response req %)) raise))))

;; Multimethods for coercing body type to the :as key
(defmulti coerce-response-body (fn [_ req _] (:as req)))

(defn coerce-clojure-body
  [_ {:keys [body] :as resp}]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
    (assoc resp :body (edn/read-string (slurp body :encoding charset)))))

(defmethod coerce-response-body :clojure [muuntaja-instance req resp]
  (coerce-clojure-body req resp))


(defmethod coerce-response-body :byte-array [_ _ resp]
  (let [ba (with-open [^InputStream xin (:body resp)
                       xout (ByteArrayOutputStream.)]
             (io/copy xin xout)
             (.toByteArray xout))]
    (assoc resp :body ba)))

(defmethod coerce-response-body :stream [_ _ resp]
  resp)

(defmethod coerce-response-body :string [_ _ {:keys [^InputStream body] :as resp}]
  (assoc resp :body (slurp body :encoding "UTF-8")))

(def default-muuntaja-options
  (-> m/default-options
      (assoc-in [:formats "text"] format.text/generic)))

(def default-muuntaja-instance (m/create default-muuntaja-options))

(defn muuntaja-instance [{:keys [muuntaja]
                          :or   {muuntaja default-muuntaja-instance}
                          :as   req}]
  (assoc req :muuntaja (m/create muuntaja)))

(defn wrap-muuntaja
  "Middleware wrapping muuntaja options."
  [client]
  (fn
    ([req]
     (client (muuntaja-instance req)))
    ([req respond raise]
     (client (muuntaja-instance req) respond raise))))


(defn- coerce-response-body* [muuntaja-instance resp]
  (let [decoded (m/decode-response-body muuntaja-instance resp)]
    (assoc resp :body decoded)))

(defmethod coerce-response-body :default
  [muuntaja-instance
   {:keys [coerce]}
   {:keys [body status] :as resp}]
  (try
    (cond
      (and (unexceptional-status? status)
           (or (nil? coerce) (= coerce :unexceptional)))
      (coerce-response-body* muuntaja-instance resp)

      (= coerce :always)
      (coerce-response-body* muuntaja-instance resp)

      (and (not (unexceptional-status? status)) (= coerce :exceptional))
      (coerce-response-body* muuntaja-instance resp)

      :else
      (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")]
        (assoc resp :body (slurp body :encoding charset))))

    (catch Exception e
      (update resp
              :hato/errors conj e))))

(defn- parse-content-type
  "Parse `s` as an RFC 2616 media type."
  [s]
  (when-let [m (re-matches #"\s*(([^/]+)/([^ ;]+))\s*(\s*;.*)?" (str s))]
    {:content-type (keyword (nth m 1))
     :content-type-params
     (->> (clojure.string/split (str (nth m 4)) #"\s*;\s*")
          (remove clojure.string/blank?)
          (map #(clojure.string/split % #"="))
          (map (fn [[k v]] [(keyword (clojure.string/lower-case k)) (clojure.string/trim v)]))
          (into {}))}))

(defn- output-coercion-response
  [req {:keys [body muuntaja]
        :or   {muuntaja default-muuntaja-instance}
        :as   resp}]
  (if body
    (coerce-response-body
      (m/create muuntaja)
      req
      (merge resp
             (parse-content-type (-> resp :headers (get "content-type")))))
    resp))

(defn wrap-output-coercion
  "Middleware converting a response body from a byte-array to a different object.
  Defaults to a String if no :as key is specified, the `coerce-response-body`
  multimethod may be extended to add additional coercions."
  [client]
  (fn
    ([req]
     (output-coercion-response req (client req)))
    ([req respond raise]
     (client req
             #(respond (output-coercion-response req %))
             raise))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn- content-type-request
  [{:keys [content-type character-encoding] :as req}]
  (if content-type
    (let [ctv (content-type-value content-type)
          ct (if character-encoding
               (str ctv "; charset=" character-encoding)
               ctv)]
      (update-in req [:headers] assoc "Content-Type" ct))
    req))

(defn wrap-content-type
  "Middleware converting a `:content-type <keyword>` option to the formal
  application/<name> format and adding it as a header."
  [client]
  (fn
    ([req]
     (client (content-type-request req)))
    ([req respond raise]
     (client (content-type-request req) respond raise))))


;; Multimethods for coercing body type to the :content-type header
(defmulti coerce-request-body (fn [muuntaja-instance req] (:is req)))

(defmethod coerce-request-body :string [_ {:keys [body] :as req}]
  ;; TODO: make sure to handle character-set information correctly.
  ;; - Problem right now: what is correctly?
  ;; - Is character set information handled by java.net.http in some way?
  #_(let [^String charset (or (-> req :content-type-params :charset) "UTF-8")]
      (assoc req :body (String. ^"[B" body charset)))
  (assoc req :body (HttpRequest$BodyPublishers/ofString body)))

(defmethod coerce-request-body :byte-array [_ {:keys [body] :as req}]
  (assoc req :body (HttpRequest$BodyPublishers/ofByteArray body)))

(defn- ^java.util.function.Function as-supplier [f]
  (reify java.util.function.Supplier
    (get [this]
      (f))))

(defmethod coerce-request-body :stream [_ {:keys [body] :as req}]
  (assoc req :body (HttpRequest$BodyPublishers/ofInputStream (as-supplier body))))

(defmethod coerce-request-body :body-publisher [_ {:keys [body] :as req}]
  (assoc req :body body))

(defmethod coerce-request-body :default
  [muuntaja-instance req]
  (let [encoded (m/encode-request-body muuntaja-instance req)]
    (assoc req :body encoded)))

(defn- request-body-coercion
  [muuntaja-instance {:keys [body] :as req}]
  (if body
    (coerce-request-body muuntaja-instance req)
    req))

(defn wrap-request-body-coercion
  "Middleware converting a request body from an object to a byte-array.
  Defaults to a String if no :as key is specified, the `coerce-response-body`
  multimethod may be extended to add additional coercions."
  [client]
  (fn
    ([{:keys [muuntaja]
       :or   {muuntaja default-muuntaja-instance}
       :as   req}]
     (client (request-body-coercion (m/create muuntaja) req)))
    ([{:keys [muuntaja]
       :or   {muuntaja default-muuntaja-instance}
       :as   req} respond raise]
     (client (request-body-coercion (m/create muuntaja) req)
             #(respond %)
             raise))))


(defn- accept-request
  [{:keys [accept] :as req}]
  (if accept
    (-> req (dissoc :accept)
        (assoc-in [:headers "accept"]
                  (content-type-value accept)))
    req))

(defn wrap-accept
  "Middleware converting the :accept key in a request to application/<type>"
  [client]
  (fn
    ([req]
     (client (accept-request req)))
    ([req respond raise]
     (client (accept-request req) respond raise))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn- accept-encoding-request
  [{:keys [accept-encoding] :as req}]
  (if accept-encoding
    (-> req
        (dissoc :accept-encoding)
        (assoc-in [:headers "accept-encoding"]
                  (accept-encoding-value accept-encoding)))
    req))

(defn wrap-accept-encoding
  "Middleware converting the :accept-encoding option to an acceptable
  Accept-Encoding header in the request."
  [client]
  (fn
    ([req]
     (client (accept-encoding-request req)))
    ([req respond raise]
     (client (accept-encoding-request req) respond raise))))

(defn detect-charset
  "Given a charset header, detect the charset, returns UTF-8 if not found."
  [content-type]
  (or
    (when-let [found (when content-type
                       (re-find #"(?i)charset\s*=\s*([^\s]+)" content-type))]
      (second found))
    "UTF-8"))

(defn- multi-param-suffix [index multi-param-style]
  (case multi-param-style
    :indexed (str "[" index "]")
    :array "[]"
    ""))

(defn generate-query-string-with-encoding [params ^String encoding multi-param-style]
  (str/join "&"
            (mapcat (fn [[k v]]
                      (if (sequential? v)
                        (map-indexed
                          #(str (URLEncoder/encode (name k) encoding)
                                (multi-param-suffix %1 multi-param-style)
                                "="
                                (URLEncoder/encode (str %2) encoding)) v)
                        [(str (URLEncoder/encode (name k) encoding)
                              "="
                              (URLEncoder/encode (str v) encoding))]))
                    params)))

(defn generate-query-string [params & [content-type multi-param-style]]
  (let [encoding (detect-charset content-type)]
    (generate-query-string-with-encoding params encoding multi-param-style)))

(defn- query-params-request
  [{:keys [query-params content-type multi-param-style]
    :or   {content-type :x-www-form-urlencoded}
    :as   req}]
  (if query-params
    (-> req (dissoc :query-params)
        (update-in [:query-string]
                   (fn [old-query-string new-query-string]
                     (if-not (empty? old-query-string)
                       (str old-query-string "&" new-query-string)
                       new-query-string))
                   (generate-query-string
                     query-params
                     (content-type-value content-type)
                     multi-param-style)))
    req))

(defn wrap-query-params
  "Middleware converting the :query-params option to a querystring on
  the request."
  [client]
  (fn
    ([req]
     (client (query-params-request req)))
    ([req respond raise]
     (client (query-params-request req) respond raise))))

(defn basic-auth-value [{:keys [user pass]}]
  (let [basic-auth (str user ":" pass)]
    (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes basic-auth "UTF-8")))))

(defn- basic-auth-request
  [req]
  (if-let [basic-auth (:basic-auth req)]
    (-> req (dissoc :basic-auth)
        (assoc-in [:headers "authorization"]
                  (basic-auth-value basic-auth)))
    req))

(defn wrap-basic-auth
  "Middleware converting the :basic-auth option into an Authorization header."
  [client]
  (fn
    ([req]
     (client (basic-auth-request req)))
    ([req respond raise]
     (client (basic-auth-request req) respond raise))))

(defn- oauth-request
  [req]
  (if-let [oauth-token (:oauth-token req)]
    (-> req (dissoc :oauth-token)
        (assoc-in [:headers "authorization"]
                  (str "Bearer " oauth-token)))
    req))

(defn wrap-oauth
  "Middleware converting the :oauth-token option into an Authorization header."
  [client]
  (fn
    ([req]
     (client (oauth-request req)))
    ([req respond raise]
     (client (oauth-request req) respond raise))))

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn- user-info-request
  [req]
  (if-let [[user pass] (parse-user-info (:user-info req))]
    (assoc req :basic-auth {:user user :pass pass})
    req))

(defn wrap-user-info
  "Middleware converting the :user-info option into a :basic-auth option"
  [client]
  (fn
    ([req]
     (client (user-info-request req)))
    ([req respond raise]
     (client (user-info-request req) respond raise))))

(defn- method-request
  [req]
  (if-let [m (:method req)]
    (-> req (dissoc :method)
        (assoc :request-method m))
    req))

(defn wrap-method
  "Middleware converting the :method option into the :request-method option"
  [client]
  (fn
    ([req]
     (client (method-request req)))
    ([req respond raise]
     (client (method-request req) respond raise))))


(defn coerce-form-params [{:keys [muuntaja
                                  content-type
                                  multi-param-style
                                  form-params
                                  form-param-encoding]
                           :or   {muuntaja default-muuntaja-instance}
                           :as   request
                           }]

  (m/encode-request-body
    (m/create muuntaja)
    request))


(defn- form-params-request
  [{:keys [form-params content-type request-method
           form-param-encoding multi-param-style]
    :or   {content-type :x-www-form-urlencoded}
    :as   req}]
  (if (and form-params (#{:post :put :patch :delete} request-method))


    (-> req
        (dissoc :form-params)
        (assoc :body form-params)
        (assoc :content-type (content-type-value content-type))
        (#(assoc % :body
                   (if (= content-type :x-www-form-urlencoded)
                     (if form-param-encoding
                       (generate-query-string-with-encoding form-params
                                                            form-param-encoding multi-param-style)
                       (generate-query-string form-params
                                              (content-type-value content-type)
                                              multi-param-style))
                     (coerce-form-params %))
                   )))
    req))

(defn wrap-form-params
  "Middleware wrapping the submission or form parameters."
  [client]
  (fn
    ([req]
     (client (form-params-request req)))
    ([req respond raise]
     (client (form-params-request req) respond raise))))

(defn- url-request
  [req]
  (if-let [url (:url req)]
    (-> req (dissoc :url) (merge (parse-url url)))
    req))

(defn wrap-url
  "Middleware wrapping request URL parsing."
  [client]
  (fn
    ([req]
     (client (url-request req)))
    ([req respond raise]
     (client (url-request req) respond raise))))

(defn- request-timing-response
  [resp start]
  (assoc resp :request-time (- (System/currentTimeMillis) start)))

(defn wrap-request-timing
  "Middleware that times the request, putting the total time (in milliseconds)
  of the request into the :request-time key in the response."
  [client]
  (fn
    ([req]
     (let [start (System/currentTimeMillis)
           resp (client req)]
       (request-timing-response resp start)))
    ([req respond raise]
     (let [start (System/currentTimeMillis)]
       (client req
               #(respond (request-timing-response % start))
               raise)))))

(defn gunzip
  "Returns a gunzip'd version of the given byte array or input stream."
  [b]
  (when b
    (when (instance? InputStream b)
      (GZIPInputStream. b))))

(defn inflate
  "Returns a zlib inflate'd version of the given byte array or InputStream."
  [b]
  (when b
    ;; This weirdness is because HTTP servers lie about what kind of deflation
    ;; they're using, so we try one way, then if that doesn't work, reset and
    ;; try the other way
    (let [stream (BufferedInputStream. b)
          _ (.mark stream 512)
          iis (InflaterInputStream. stream)
          readable? (try (.read iis) true
                         (catch ZipException _ false))
          _ (.reset stream)
          iis' (if readable?
                 (InflaterInputStream. stream)
                 (InflaterInputStream. stream (Inflater. true)))]

      iis')))

;; Multimethods for Content-Encoding dispatch automatically
;; decompressing response bodies
(defmulti decompress-body
          (fn [resp]
            (when-let [encoding (get-in resp [:headers "content-encoding"])]
              (str/lower-case encoding))))

(defmethod decompress-body "gzip"
  [resp]
  (update resp :body gunzip))

(defmethod decompress-body "deflate"
  [resp]
  (update resp :body inflate))

(defmethod decompress-body :default [resp]
  resp)

(defn- decompression-request
  [req]
  (if (false? (opt req :decompress-body))
    req
    (update-in req [:headers "accept-encoding"]
               #(str/join ", " (remove nil? [% "gzip, deflate"])))))

(defn- decompression-response
  [req resp]
  (if (false? (opt req :decompress-body))
    resp
    (decompress-body resp)))

(defn wrap-decompression
  "Middleware handling automatic decompression of responses from web servers. If
  :decompress-body is set to false, does not automatically set `Accept-Encoding`
  header or decompress body."
  [client]
  (fn
    ([req]
     (decompression-response req (client (decompression-request req))))
    ([req respond raise]
     (client (decompression-request req)
             #(respond (decompression-response req %))
             raise))))

(defn- nest-params
  [request param-key]
  (if-let [params (request param-key)]
    (assoc request param-key
                   (prewalk
                     #(if (and (vector? %) (map? (second %)))
                        (let [[fk m] %]
                          (reduce
                            (fn [m [sk v]]
                              (assoc m (str (name fk) "[" (name sk) "]") v))
                            {}
                            m))
                        %)
                     params))
    request))

(defn nest-params-request
  "Middleware wrapping nested parameters for query strings."
  [{:keys [content-type flatten-nested-keys] :as req}]
  (when (and (some? flatten-nested-keys)
             (or (some? (opt req :ignore-nested-query-string))
                 (some? (opt req :flatten-nested-form-params))))
    (throw (IllegalArgumentException.
             (str "only :flatten-nested-keys or :ignore-nested-query-string/"
                  ":flatten-nested-form-params may be specified, not both"))))
  (let [form-urlencoded? (or (nil? content-type)
                             (= content-type :x-www-form-urlencoded))
        flatten-form? (opt req :flatten-nested-form-params)
        nested-keys (or flatten-nested-keys
                        (cond-> []
                                (not (opt req :ignore-nested-query-string))
                                (conj :query-params)

                                (and form-urlencoded?
                                     (true? flatten-form?))
                                (conj :form-params)))]
    (reduce nest-params req nested-keys)))

(defn wrap-nested-params
  "Middleware wrapping nested parameters for query strings."
  [client]
  (fn
    ([req]
     (client (nest-params-request req)))
    ([req respond raise]
     (client (nest-params-request req) respond raise))))

(defn multipart-request
  "Adds appropriate body and header if making a multipart request."
  [{:keys [multipart] :as req}]
  (if multipart
    (let [b (multipart/boundary)]
      (-> req
          (dissoc :multipart)
          (assoc :body (multipart/body multipart b))
          (update :headers assoc "content-type" (str "multipart/form-data; boundary=" b))))
    req))

(defn wrap-multipart
  "Middleware wrapping multipart requests."
  [client]
  (fn
    ([req]
     (client (multipart-request req)))
    ([req respond raise]
     (client (multipart-request req) respond raise))))

(def default-middleware
  "The default list of middleware hato uses for wrapping requests."
  [wrap-request-timing
   wrap-muuntaja
   wrap-query-params
   wrap-basic-auth
   wrap-oauth
   wrap-user-info
   wrap-url

   wrap-decompression
   wrap-output-coercion
   wrap-exceptions
   wrap-accept
   wrap-accept-encoding
   wrap-multipart

   wrap-request-body-coercion
   wrap-form-params
   wrap-nested-params
   wrap-content-type
   wrap-method
   ])

(defn wrap-request
  "Returns a batteries-included HTTP request function corresponding to the given
  core client. See default-middleware for the middleware wrappers that are used
  by default, which you can enrich with your muuntaja options or instance according
  to your needs for different content types."
  ([request]
   (wrap-request request default-middleware))
  ([request middleware]
   (reduce (fn [req middleware-wrapper] (middleware-wrapper req))
           request
           middleware)))
