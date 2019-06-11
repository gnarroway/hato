(ns hato.client
  "Core implementation of an HTTP client wrapping JDK11's java.net.http.HttpClient."
  (:refer-clojure :exclude [get])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hato.middleware :as middleware])
  (:import
    (java.net.http
      HttpClient$Redirect
      HttpClient$Version
      HttpResponse$BodyHandler
      HttpResponse$BodyHandlers
      HttpRequest$BodyPublisher
      HttpRequest$BodyPublishers HttpResponse HttpClient HttpRequest HttpClient$Builder)
    (java.net CookiePolicy CookieManager URI ProxySelector)
    (javax.net.ssl KeyManagerFactory TrustManagerFactory SSLContext)
    (java.security KeyStore)
    (java.time Duration)
    (java.util.function Function)
    (java.io File)))


(defn- ->BodyHandler
  "Returns a BodyHandler.

  `as` can be a BodyHandler or some keyword that maps to one.

  Defaults to a byte array if nothing matches.

  :string is handled by middleware (coercing from byte array) rather than native
  BodyHandler as it conflicts with compressed responses (e.g. gzip).

  https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandler.html"
  [as]
  (if (instance? HttpResponse$BodyHandler as)
    as
    (case as
      :byte-array (HttpResponse$BodyHandlers/ofByteArray)
      :stream (HttpResponse$BodyHandlers/ofInputStream)
      :discarding (HttpResponse$BodyHandlers/discarding)

      (HttpResponse$BodyHandlers/ofByteArray))))

(defn- ->BodyPublisher
  "Returns a BodyPublisher.

  If not provided a BodyPublisher explicitly, tries to create one
  based on the request (mostly the type of the body).

  Defaults to a string publisher if nothing matches.

  https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.BodyPublisher.html"
  [{:keys [body content-type] :as req}]
  (if (instance? HttpRequest$BodyPublisher body)
    body
    (cond
      (nil? body) (HttpRequest$BodyPublishers/noBody)
      (bytes? body) (HttpRequest$BodyPublishers/ofByteArray body)
      (instance? File body) (HttpRequest$BodyPublishers/ofFile (.toPath body))
      (and (= :json content-type)
           (coll? body)) (HttpRequest$BodyPublishers/ofString (middleware/json-encode body))
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
   :version     (-> response (.version) (.name) Version->kw)
   :http-client http-client
   :request     (assoc request :http-request (.request response))})

;;;

(def cookie-policies
  {:none            (CookiePolicy/ACCEPT_NONE)
   :all             (CookiePolicy/ACCEPT_ALL)
   :original-server (CookiePolicy/ACCEPT_ORIGINAL_SERVER)})

(defn cookie-manager
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

(defn ssl-context
  "Creates an SSLContext.

  `keystore` is an URL e.g. (io/resource somepath.p12)
  `keystore-type` is the type of keystore to create [note: not the type of the file] (default: pkcs12)
  `keystore-pass` is the password for the keystore
  `trust-store` is an URL e.g. (io/resource cacerts.p12)
  `trust-store-type` is the type of trust store to create [note: not the type of the file] (default: pkcs12)
  `trust-store-pass` is the password for the trust store"
  [{:keys [keystore keystore-type keystore-pass trust-store trust-store-type trust-store-pass]
    :or   {keystore-type "pkcs12" trust-store-type "pkcs12"}
    }]
  (with-open [kss (io/input-stream keystore)
              tss (io/input-stream trust-store)]
    (let [ks (doto (KeyStore/getInstance keystore-type)
               (.load kss (char-array keystore-pass)))

          kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                (.init ks (char-array keystore-pass)))

          ts (doto (KeyStore/getInstance trust-store-type)
               (.load tss (char-array trust-store-pass)))

          tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                (.init ts))]

      (doto (SSLContext/getInstance "TLS")
        (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil)))))

(defn build-http-client
  "Creates an HttpClient from an option map.

  Options:
  `authenticator` a java.net.Authenticator
  `cookie-handler` a java.net.CookieHandler
  `cookie-policy` :none, :all, :original-server. cookie-handler takes precedence if specified
  `connect-timeout` in milliseconds
  `follow-redirects` :never (default), :normal, :always
  `priority` an integer between 1 and 256 inclusive for HTTP/2 requests
  `proxy` a java.net.ProxySelector or :no-proxy
  `ssl-context` an javax.net.ssl.SSLContext
  `ssl-parameters a javax.net.ssl.SSLParameters
  `version` :http-1.1 :http-2"
  [{:keys [authenticator
           cookie-handler
           cookie-policy
           connect-timeout
           follow-redirects
           priority
           proxy
           ssl-context
           ssl-parameters
           version]
    :as   opts
    }]
  (let [builder (HttpClient/newBuilder)]
    (when authenticator
      (.authenticator builder authenticator))

    (when-let [ch (or cookie-handler (cookie-manager cookie-policy))]
      (.cookieHandler builder ch))

    (when connect-timeout
      (.connectTimeout builder (Duration/ofMillis connect-timeout)))

    (when follow-redirects
      (.followRedirects builder (->Redirect follow-redirects)))

    (when priority
      (.priority builder priority))

    (when proxy
      (.proxy builder (->ProxySelector proxy)))

    (when ssl-context
      (.sslContext builder ssl-context))

    (when ssl-parameters
      (.sslParameters builder ssl-parameters))

    (when version
      (.version builder (->Version version)))

    (.build builder)))

(defn ring-request->HttpRequest
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
  (let [builder (HttpRequest/newBuilder
                  (URI. (name scheme)
                        nil
                        server-name
                        (or server-port -1)
                        uri
                        query-string
                        nil))]
    (.method builder (str/upper-case (name request-method)) (->BodyPublisher req))

    (when expect-continue
      (.expectContinue builder expect-continue))

    (when timeout
      (.timeout builder (Duration/ofMillis timeout)))

    (when version
      (.version builder (->Version version)))

    (doseq [[header-n header-v] headers]
      (.header builder header-n header-v))

    (.build builder)))

(defn request*
  [{:keys [http-client async? as]
    :as   req
    :or   {http-client (build-http-client req)}} & [respond raise]]
  (let [http-request (ring-request->HttpRequest req)
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
                (raise e))))))))

(def request (middleware/wrap-request request*))

(defn- configure-and-execute
  "Convenience wrapper"
  [method url {:keys [async?] :as opts} & [respond raise]]
  (if-not async?
    (request (merge opts {:request-method method :url url}))
    (request (merge opts {:request-method method :url url}) (or respond identity) (or raise identity))))

(def get (partial configure-and-execute :get))
(def post (partial configure-and-execute :post))
(def put (partial configure-and-execute :put))
(def patch (partial configure-and-execute :patch))
(def delete (partial configure-and-execute :delete))
(def head (partial configure-and-execute :head))
(def options (partial configure-and-execute :options))