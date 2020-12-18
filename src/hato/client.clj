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
    (java.net CookiePolicy CookieManager URI ProxySelector Authenticator PasswordAuthentication CookieHandler UnknownHostException)
    (javax.net.ssl KeyManagerFactory TrustManagerFactory SSLContext SSLException)
    (java.security KeyStore)
    (java.time Duration)
    (java.util.function Function Supplier BiFunction)
    (java.io File InputStream InterruptedIOException IOException)
    (java.util.concurrent CompletableFuture CompletionException ExecutionException)))

(defn- ->Authenticator
  [v]
  (if (instance? Authenticator v)
    v
    (let [{:keys [user pass]} v]
      (when (and user pass)
        (proxy [Authenticator] []
          (getPasswordAuthentication []
            (PasswordAuthentication. user (char-array pass))))))))

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

          tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
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
  {:uri         (str (.uri response))
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

;;;

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
           version]}]
  (cond-> (HttpClient/newBuilder)
    connect-timeout (.connectTimeout (Duration/ofMillis connect-timeout))
    redirect-policy (.followRedirects (->Redirect redirect-policy))
    priority (.priority priority)
    proxy (.proxy (->ProxySelector proxy))
    version (.version (->Version version))
    ssl-context (.sslContext (->SSLContext ssl-context))
    ssl-parameters (.sslParameters ssl-parameters)
    authenticator (with-authenticator authenticator)
    (or cookie-handler cookie-policy) (with-cookie-handler cookie-handler cookie-policy)
    true .build))

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
    :or   {request-method :get}
    :as   req}]
  (cond-> (HttpRequest/newBuilder
           (URI. (str (name scheme)
                      "://"
                      server-name
                      (some->> server-port (str ":"))
                      uri
                      (some->> query-string (str "?")))))
    expect-continue (.expectContinue expect-continue)
    version (.version (->Version version))
    headers (with-headers headers)
    timeout (.timeout (Duration/ofMillis timeout))
    true (-> (.method (str/upper-case (name request-method))
                      (->BodyPublisher req))
             .build)))

(defn unwrap-async-exception
  "Helper function to unwrap common async exceptions to get the root cause such as
  an IOException."
  [^Exception ex]
  (if (or (instance? CompletionException ex)
          (instance? ExecutionException ex))
    (if-let [cause (.getCause ex)]
      (unwrap-async-exception cause)
      ex)
    ex))

(def retry-exceptions
  "Common exceptions to retry requests on."
  #{IOException})

(def non-retry-exceptions
  "Common exceptions to not retry requests on. Borrowed from Apache HttpClient's
  `DefaultHttpRequestRetryHandler`."
  #{InterruptedIOException UnknownHostException SSLException})

(defn idempotent-request?
  "Checks to see if the request is idempotent. It is considered idempotent if the
  request method is one of `:get`, `:head`, or `:options`."
  [req]
  (contains? #{:get :head :options} (:request-method req)))

(defn make-retry-handler
  "Constructs a basic retry handler. Options are:

  :retry-exceptions     => A collection of exceptions required for us to retry the request on.
                           Defaults to `retry-exceptions`.
  :non-retry-exceptions => A collection of exceptions to cause us to skip retrying a specific
                           request. Defaults to `non-retry-exceptions`.
  :max-retry-count      => The maximum number of retries before giving up. Defaults to 3."
  [{:keys [retry-exceptions non-retry-exceptions max-retry-count]
    :or   {retry-exceptions     retry-exceptions
           non-retry-exceptions non-retry-exceptions
           max-retry-count      3}}]
  (fn [_resp ex req retry-count]
    (and (idempotent-request? req)
         (some #(instance? % ex) retry-exceptions)
         (not (some #(instance? % ex) non-retry-exceptions))
         (< retry-count max-retry-count))))

(def default-retry-handler
  "A default retry handler which will retry if there is an error with any known retriaretryble
  exceptions while skipping non-retry exceptions. By default, this will only retry idempotent
  requests (i.e. GET/HEAD/OPTIONS)."
  (make-retry-handler {}))

(defn request-with-retries*
  "Makes a request retrying on failures as defined by retry-handler"
  [http-client http-request body-handler req retry-handler retry-count]
  (-> (.sendAsync http-client http-request body-handler)
      (.handle
        (reify BiFunction
          (apply [_ resp e]
            [resp (unwrap-async-exception e)])))
      (.thenCompose
        (reify Function
          (apply [_ [resp e]]
            (let [retry-fut (if retry-handler
                              (retry-handler resp e req retry-count)
                              (CompletableFuture/completedFuture false))]
              (-> (if (instance? CompletableFuture retry-fut)
                    ^CompletableFuture retry-fut
                    (CompletableFuture/completedFuture retry-fut))
                  (.thenCompose
                    (reify Function
                      (apply [_ retry?]
                        (if retry?
                          (request-with-retries* http-client http-request body-handler req retry-handler (inc retry-count))
                          (if e
                            (CompletableFuture/failedFuture e)
                            (CompletableFuture/completedFuture
                              (response-map {:request     req
                                             :http-client http-client
                                             :response    resp}))))))))))))))

(defn request*
  [{:keys [http-client async? retry-handler]
    :as   req} & [respond raise]]
  (let [^HttpClient http-client (if (instance? HttpClient http-client) http-client (build-http-client http-client))
        http-request            (ring-request->HttpRequest req)
        retry-handler           (if (= :auto retry-handler) default-retry-handler retry-handler)
        body-handler            (HttpResponse$BodyHandlers/ofInputStream)
        resp-fut                (request-with-retries* http-client http-request body-handler req retry-handler 0)]
    (if async?
      (-> resp-fut
          (.thenApply
            (reify Function
              (apply [_ resp-map]
                (respond resp-map))))
          (.exceptionally
            (reify Function
              (apply [_ e]
                (raise (unwrap-async-exception e))))))
      (try @resp-fut
           (catch Exception e
             (throw (unwrap-async-exception e)))))))

(def default-wrapped-request (middleware/wrap-request request*))

(defn request
  [req & [respond raise]]
  (if (:async? req)
    (default-wrapped-request req (or respond identity) (or raise #(throw %)))
    (default-wrapped-request req)))

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