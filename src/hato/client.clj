(ns hato.client
  "Core implementation of an HTTP client wrapping JDK11's java.net.http.HttpClient."
  (:refer-clojure :exclude [get])
  (:require
   [clojure.string :as str]
   [hato.middleware :as middleware]
   [clojure.java.io :as io])
  (:import
    (java.net.http
      HttpClient$Redirect
      HttpClient$Version
      HttpResponse$BodyHandlers
      HttpRequest$BodyPublisher
      HttpRequest$BodyPublishers HttpResponse HttpClient HttpRequest HttpClient$Builder HttpRequest$Builder)
    (java.net CookiePolicy CookieManager URI ProxySelector Authenticator PasswordAuthentication CookieHandler)
    (javax.net.ssl KeyManagerFactory TrustManagerFactory SSLContext)
    (java.security KeyStore)
    (java.time Duration)
    (java.util.function Function Supplier)
    (java.io File InputStream)
    (clojure.lang ExceptionInfo)
    (java.nio CharBuffer ByteBuffer)
    (java.nio.charset CharsetEncoder CharsetDecoder Charset)))

(defn- ->Authenticator
  [v]
  (if (instance? Authenticator v)
    v
    (let [{:keys [user pass encoding]} v]
      (when (and user pass)
        (proxy [Authenticator] []
          (getPasswordAuthentication []
            (PasswordAuthentication. user
              (cond ;; support passwords as String, Sequential or byte/char-array
                (string? pass)     (.toCharArray ^String pass)
                (sequential? pass) (char-array pass)
                (bytes? pass)  (-> (or encoding "UTF-8")
                                   Charset/forName
                                   .newDecoder
                                   (.decode (ByteBuffer/wrap pass))
                                   .array)
                ;; presumably already a char-array
                :else pass))))))))

(defn- ->BodyHandler
  "Returns a BodyHandler.

  Always returns InputStream that are coerced in middleware.

  https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandler.html"
  [_]
  (HttpResponse$BodyHandlers/ofInputStream))

(defn- ->BodyPublisher
  "Returns a BodyPublisher.

  If not provided a BodyPublisher explicitly, tries to create one
  based on the request.

  Defaults to a string publisher if nothing matches.

  https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.BodyPublisher.html"
  [{:keys [body]}]
  (if (instance? HttpRequest$BodyPublisher body)
    body
    (cond
      (nil? body) (HttpRequest$BodyPublishers/noBody)
      (bytes? body) (HttpRequest$BodyPublishers/ofByteArray body)
      (instance? File body) (HttpRequest$BodyPublishers/ofFile (.toPath ^File body))
      (instance? InputStream body) (HttpRequest$BodyPublishers/ofInputStream (reify Supplier
                                                                               (get [_]
                                                                                 body)))
      :else (HttpRequest$BodyPublishers/ofString body))))

(defn- ->ProxySelector
  "Returns a ProxySelector.

  `v` should be :no-proxy, to always return Proxy.NO_PROXY, or an instance of a ProxySelector.
  If not, returns the system default ProxySelector silently.

  https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/ProxySelector.html"
  [v]
  (cond
    (instance? ProxySelector v) v
    (= :no-proxy v) (HttpClient$Builder/NO_PROXY)
    :else (ProxySelector/getDefault)))

(defn- ->Redirect
  "Returns a HttpClient$Redirect.

  `v` should be a keyword corresponding to a Redirect, or a Redirect itself.

  https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.Redirect.html"
  [v]
  (if (instance? HttpClient$Redirect v)
    v
    (-> v name str/upper-case HttpClient$Redirect/valueOf)))

(defn- load-keystore
  ^KeyStore [store store-type store-pass]
  (when store
    (with-open [kss (io/input-stream store)]
      (doto (KeyStore/getInstance store-type)
        (.load kss (char-array store-pass))))))

(defn ->SSLContext
  "Returns an SSLContext.

  `v` should be an SSLContext, or a map with the following keys:

  `keystore` is an URL e.g. (io/resource somepath.p12)
  `keystore-pass` is the password for the keystore
  `keystore-type` is the type of keystore to create [note: not the type of the file] (default: pkcs12)
  `trust-store` is an URL e.g. (io/resource cacerts.p12)
  `trust-store-pass` is the password for the trust store
  `trust-store-type` is the type of trust store to create [note: not the type of the file] (default: pkcs12).

  If either `keystore` or `trust-store` are not provided, the respective default will be used, which can be overridden
  by java options `-Djavax.net.ssl.keyStore` and `-Djavax.net.ssl.trustStore`, respectively."
  [v]
  (if (instance? SSLContext v)
    v
    (let [{:keys [keystore keystore-type keystore-pass trust-store trust-store-type trust-store-pass]
           :or   {keystore-type "pkcs12" trust-store-type "pkcs12"}} v

          ks (load-keystore keystore keystore-type keystore-pass)
          ts (load-keystore trust-store trust-store-type trust-store-pass)
          kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                (.init ks (char-array keystore-pass)))
          tmf (doto (TrustManagerFactory/getInstance
                      (TrustManagerFactory/getDefaultAlgorithm))
                (.init ts))]

      (doto (SSLContext/getInstance "TLS")
        (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil)))))

(defn- ->Version
  "Returns a HttpClient$Version.

  `v` should be a keyword corresponding to a Version, or a Version itself
  e.g. :http-1.1 -> HTTP_1_1, :http-2 -> HTTP_2

  https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.Version.html"
  [v]
  (if (instance? HttpClient$Version v)
    v
    (-> v name str/upper-case (str/replace #"[-\.]" "_") HttpClient$Version/valueOf)))

(defn- Version->kw
  "Turns string value of an HttpClient$Version into a keyword.
  e.g. HTTP_1_1 -> :http-1.1"
  [s]
  (-> s (str/replace #"^HTTP_(.+)$" "http-$1") (str/replace "_" ".") keyword))

(defn- response-map
  "Creates a response map.
  This will then be passed back through the middleware before being returned to the client.

  `request` is the map of request options output by `make-request`
  `response` is the raw HttpResponse"
  [{:keys [request ^HttpResponse response http-client]}]
  {:uri         (.toString (.uri response))
   :status      (.statusCode response)
   :body        (.body response)
   :headers     (->> (.map (.headers response))
                     (map (fn [[k v]] (if (> (count v) 1) [k v] [k (first v)])))
                     (into {}))
   :version     (-> response .version .name Version->kw)
   :http-client http-client
   :request     (assoc request :http-request (.request response))})

(def cookie-policies
  {:none            (CookiePolicy/ACCEPT_NONE)
   :all             (CookiePolicy/ACCEPT_ALL)
   :original-server (CookiePolicy/ACCEPT_ORIGINAL_SERVER)})

(defn- cookie-manager
  "Creates a CookieManager.

  `cookie-policy` maps to a CookiePolicy, accepting :all, :none, :original-server (default), or a CookiePolicy implementation.

  Invalid values will result in a CookieManager with the default policy (original server).

  https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookieManager.html"
  [cookie-policy]
  (when cookie-policy
    (let [cm (CookieManager.)]
      (if (instance? CookiePolicy cookie-policy)
        (doto cm (.setCookiePolicy cookie-policy))

        (if-let [cp (cookie-policies cookie-policy)]
          (doto cm (.setCookiePolicy cp))
          cm)))))

(defn- with-headers
  ^HttpRequest$Builder [builder headers]
  (reduce-kv
    (fn [^HttpRequest$Builder b ^String hk ^String hv]
      (.header b hk hv))
    builder
    headers))

(defn- with-authenticator
  ^HttpClient$Builder [^HttpClient$Builder b a]
  (if-some [a (->Authenticator a)]
    (.authenticator b a)
    b))

(defn- with-cookie-handler
  ^HttpClient$Builder [^HttpClient$Builder b cookie-handler cookie-policy]
  (if-some [^CookieHandler ch (or cookie-handler (cookie-manager cookie-policy))]
    (.cookieHandler b ch)
    b))

;;;

(defn ^HttpClient build-http-client
  "Creates an HttpClient from an option map.

  Options:
  `authenticator` a java.net.Authenticator or {:user \"user\" :pass \"pass\"}
  `cookie-handler` a java.net.CookieHandler
  `cookie-policy` :none, :all, :original-server. cookie-handler takes precedence if specified
  `connect-timeout` in milliseconds
  `redirect-policy` :never (default), :normal, :always
  `priority` an integer between 1 and 256 inclusive for HTTP/2 requests
  `proxy` a java.net.ProxySelector or :no-proxy
  `ssl-context` an javax.net.ssl.SSLContext
  `ssl-parameters a javax.net.ssl.SSLParameters
  `version` :http-1.1 :http-2"
  [{:keys [authenticator
           cookie-handler
           cookie-policy
           connect-timeout
           redirect-policy
           priority
           proxy
           ssl-context
           ssl-parameters
           version]
    :or {redirect-policy :normal ;; https to http not allowed
         version :http-2}}]      ;; will fallback to version 1.1
  (cond-> (HttpClient/newBuilder)
          connect-timeout (.connectTimeout (Duration/ofMillis connect-timeout))
          redirect-policy (.followRedirects (->Redirect redirect-policy))
          priority        (.priority priority)
          proxy           (.proxy  (->ProxySelector proxy))
          version         (.version (->Version version))
          ssl-context     (.sslContext (->SSLContext ssl-context))
          ssl-parameters  (.sslParameters ssl-parameters)
          authenticator   (with-authenticator authenticator)
          (or cookie-handler cookie-policy) (with-cookie-handler cookie-handler cookie-policy)
          true .build)
  )

(defn ^HttpRequest ring-request->HttpRequest
  "Creates an HttpRequest from a ring request map.

  -- Standard ring request
  Aside from headers, these will be generated via middleware by simply passing a single :url option.

  `scheme` The transport protocol, :http or :https
  `server-name` hostname e.g. google.com
  `uri` The resource excluding query string and '?', starting with '/'.
  `server-port` Integer
  `query-string` Query string, if present
  `request-method` Lowercase keyword corresponding to a HTTP request method, such as :get or :post.
  `headers` Map of lower case strings to header values, concatenated with ',' when multiple values for a key.

  -- Options specific to HttpRequest
  `expect-continue` boolean (default false)
  `timeout` in milliseconds
  `version` :http-1.1 :http-2"
  [{:keys [scheme
           server-name
           uri
           server-port
           query-string
           headers
           request-method
           timeout
           version
           expect-continue]
    :or   {request-method :get
           scheme :https
           timeout 2000} ;; a sensible default?
    :as   req}]
  (cond-> (HttpRequest/newBuilder
            (URI. (str (name scheme)
                       "://"
                       server-name
                       (some->> server-port (str ":"))
                       uri
                       (some->> query-string (str "?")))))
          expect-continue (.expectContinue expect-continue)
          version         (.version  (->Version version))
          headers         (with-headers headers)
          true
          (-> (.method (str/upper-case (name request-method))
                       (->BodyPublisher req))
              (.timeout (Duration/ofMillis timeout))
              .build)))

(defn request*
  [{:keys [http-client async? as]
    :as   req} & [respond raise]]
  (let [^HttpClient http-client (if (instance? HttpClient http-client) http-client (build-http-client http-client))
        http-request (ring-request->HttpRequest req)
        bh (->BodyHandler as)]
    (if-not async?
      (let [resp (.send http-client http-request bh)]
        (response-map
         {:request     req
          :http-client http-client
          :response    resp}))

      (-> (.sendAsync http-client http-request bh)
          (.thenApply
           (reify Function
             (apply [_ resp]
               (respond
                (response-map
                 {:request     req
                  :http-client http-client
                  :response    resp})))))
          (.exceptionally
           (reify Function
             (apply [_ e]
               (let [cause (.getCause ^Exception e)]
                 (if (instance? ExceptionInfo cause)
                   (raise cause)
                   (raise e))))))))))

(defn request
  [req & [respond raise]]
  (let [wrapped (middleware/wrap-request request*)]
    (if (:async? req)
      (wrapped req (or respond identity) (or raise #(throw %)))
      (wrapped req))))

(defn- configure-and-execute
  "Convenience wrapper"
  [method url & [opts respond raise]]
  (request (merge opts {:request-method method :url url}) respond raise))

(def get (partial configure-and-execute :get))
(def post (partial configure-and-execute :post))
(def put (partial configure-and-execute :put))
(def patch (partial configure-and-execute :patch))
(def delete (partial configure-and-execute :delete))
(def head (partial configure-and-execute :head))
(def options (partial configure-and-execute :options))