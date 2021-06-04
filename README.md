# hato

[![Clojars Project](https://img.shields.io/clojars/v/hato.svg)](https://clojars.org/hato)

[![CircleCI](https://circleci.com/gh/gnarroway/hato.svg?style=svg)](https://circleci.com/gh/gnarroway/hato)

An HTTP client for Clojure, wrapping JDK 11's [HttpClient](https://openjdk.java.net/groups/net/httpclient/intro.html).

It supports both HTTP/1.1 and HTTP/2, with synchronous and asynchronous execution modes as well as websockets.

In general, it will feel familiar to users of http clients like [clj-http](https://github.com/dakrone/clj-http).
The API is designed to be idiomatic and to make common tasks convenient, whilst
still allowing the underlying HttpClient to be configured via native Java objects.

## Status

hato has a stable API and is used in production for both synchronous and asynchronous use cases.
Please try it out and raise any issues you may find.

## Installation

hato requires JDK 11 and above. If you are running an older version of Java, please look at [clj-http](https://github.com/dakrone/clj-http).

For Leinengen, add this to your project.clj

```clojure
[hato "0.8.1"]
```

## Quickstart

The main client is available in `hato.client`.

Require it to get started and make a request:

```clojure

(ns my.app
  (:require [hato.client :as hc]))

  (hc/get "https://httpbin.org/get")
  ; =>
  ; {:request-time 112
  ;  :status 200
  ;  :body "{\"url\" ...}"
  ;  ...}
```

## Usage

### Building a client

Generally, you want to make a reusable client first. This will give you nice things like
persistent connections and connection pooling.

This can be done with `build-http-client`:

```clojure
; Build the client
(def c (hato/build-http-client {:connect-timeout 10000
                                :redirect-policy :always}))

; Use it for multiple requests
(hc/get "https://httpbin.org/get" {:http-client c})
(hc/head "https://httpbin.org/head" {:http-client c})
```

#### build-http-client options

`authenticator` Used for non-preemptive basic authentication. See the `basic-auth` request
  option for pre-emptive authentication. Accepts:

 - A map of `{:user "username" :pass "password"}`
 - a [`java.net.Authenticator`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/Authenticator.html)

`cookie-handler` a [`java.net.CookieHandler`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookieHandler.html)
  if you need full control of your cookies. See `cookie-policy` for a more convenient option.

`cookie-policy` Determines whether to accept cookies. The `cookie-handler` option will take precedence if it is set.
  If an invalid option is provided, a CookieManager with the default policy (original-server)
  will be created. Valid options:

 - `:none` Accepts no cookies
 - `:all`  Accepts all cookies
 - `:original-server` (default) Accepts cookies from original server
 - An implementation of [`java.net.CookiePolicy`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookiePolicy.html).

`connect-timeout` Timeout to making a connection, in milliseconds (default: unlimited).

`executor` Sets the thread executor.

`redirect-policy` Sets the redirect policy.

  - `:never` (default) Never follow redirects.
  - `:normal` Always redirect, except from HTTPS URLs to HTTP URLs.
  - `:always` Always redirect

`priority` an integer between 1 and 256 (both inclusive) for HTTP/2 requests

`proxy` Sets a proxy selector. If not set, uses the default system-wide ProxySelector,
  which can be configured by Java opts such as `-Dhttp.proxyHost=somehost` and `-Dhttp.proxyPort=80`
  (see [all options](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/doc-files/net-properties.html#Proxies)).
  Also accepts:

  - `:no-proxy` to explicitly disable the default behavior, implying a direct connection; or
  - a [`java.net.ProxySelector`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/ProxySelector.html)

`ssl-context` Sets the SSLContext. If not specified, uses the default `(SSLContext/getDefault)`. Accepts:

  - a map of `:keystore` `:keystore-pass` `:trust-store` `:trust-store-pass`. See client authentication examples for more details.
  - an [`javax.net.ssl.SSLContext`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLContext.html)

`ssl-parameters` a `javax.net.ssl.SSLParameters`

`version` Sets preferred HTTP protocol version.
  - `:http-1.1` prefer HTTP/1.1
  - `:http-2` (default) tries to upgrade to HTTP/2, falling back to HTTP/1.1

### Making requests

The core function for making requests is `hato.client/request`, which takes a [ring](https://github.com/ring-clojure/ring/blob/master/SPEC)
request and returns a response. Convenience wrappers are provided for the http verbs (`get`, `post`, `put` etc.).

```clojure
; The main request function
(hc/request {:method :get, :url "https://httpbin.org/get"})

; Convenience wrappers
(hc/get "https://httpbin.org/get")
(hc/get "https://httpbin.org/get" {:as :json})
(hc/post "https://httpbin.org/post" {:body "{\"a\": 1}" :content-type :json})
```

#### request options

`method`Lowercase keyword corresponding to a HTTP request method, such as :get or :post.

`url` An absolute url to the requested resource (e.g. `"http://moo.com/api/1"`).

`accept` Sets the `accept` header. a keyword (e.g. `:json`, for any application/* type) or string (e.g. `"text/html"`) for anything else.

`accept-encoding` List of string/keywords (e.g. `[:gzip]`). By default, "gzip, deflate" will be concatenated
  unless `decompress-body?` is false.

`content-type` a keyword (e.g. `:json`, for any application/* type) or string (e.g. "text/html") for anything else.
  Sets the appropriate header.

`body` the body of the request. This should be a string, byte array, input stream,
  or a [`java.net.http.HttpRequest$BodyPublisher`](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.BodyPublisher.html).
  To send a clojure map as json (or some other format), use the `form-params` option with the appropriate `content-type`.

`as` Return response body in a certain format. Valid options:

  - Return an object type: `:string` (default), `:byte-array`, `:stream`,
  - `:auto`, to automatically coerce response body based on the response (e.g. `content-type`).
    This is an alpha feature and the implementation may change.
  - Coerce response body with certain format: `:json`, `:json-string-keys`,
  `:clojure`, `:transit+json`, `:transit+msgpack`. JSON and transit
  coercion require optional dependencies [cheshire](https://github.com/dakrone/cheshire) (5.9.0 or later) and
  [com.cognitect/transit-clj](https://github.com/cognitect/transit-clj) to be installed, respectively.

`coerce` Determine which status codes to coerce response bodies. `:unexceptional` (default), `:always`, `:exceptional`.
  This presently only has an effect for json coercions.

`query-params` A map of options to turn into a query string. See usage examples for details.

`form-params` A map of options that will be sent as the body, depending on the `content-type` option. For example,
  set `:content-type :json` to coerce the form-params to a json string (requires [cheshire](https://github.com/dakrone/cheshire)).
  See usage examples for details.

`multi-param-style` Decides how to represent array values when converting `query-params` into a query string. Accepts:

  - When unset (default), a repeating parameter `a=1&a=2&a=3`
  - `:array`, a repeating param with array suffix: `a[]=1&a[]=2&a[]=3`
  - `:index`, a repeating param with array suffix and index: `a[0]=1&a[1]=2&a[2]=3`

`multipart` A sequence of maps with the following keys:

  - `:name` The name of the param
  - `:part-name` To preserve the order of entities, `:name` will be used as the part name unless `:part-name` is specified
  - `:content` The part's data. May be a `String`, `InputStream`, `Reader`, `File`, `char-array`, or a `byte-array`
  - `:file-name` The part's file name. If the `:content` is a `File`, it will use `.getName` by default but may be overridden.
  - `:content-type` The part's content type. By default, if `:content` is a `String` it will be `text/plain; charset=UTF-8`
                    and if `:content` is a `File` it will attempt to guess the best content type or fallback to
                    `application/octet-stream`.

`headers` Map of lower case strings to header values, concatenated with ',' when multiple values for a key.
  This is presently a slight incompatibility with clj-http, which accepts keyword keys and list values.

`basic-auth` Performs basic authentication (sending `Basic` authorization header). Accepts `{:user "user" :pass "pass"}`
  Note that basic auth can also be passed via the `url` (e.g. `http://user:pass@moo.com`)

`oauth-token` String, will set `Bearer` authorization header

`decompress-body?` By default, sets request header to accept "gzip, deflate" encoding, and decompresses the response.
  Set to `false` to turn off this behaviour.

`throw-exceptions?` By default, the client will throw exceptions for exceptional response statuses. Set this to
  `false` to return the response without throwing.

`async?` Boolean, defaults to false. See below section on async requests.

`http-client` An `HttpClient` created by `build-http-client` or other means. For single-use clients, it also
  accepts a map of the options accepted by `build-http-client`.

`expect-continue` Requests the server to acknowledge the request before sending the body. This is disabled by default.

`timeout` Timeout to receiving a response, in milliseconds (default: unlimited).

`version` Sets preferred HTTP protocol version per request.

  - `:http-1.1` prefer HTTP/1.1
  - `:http-2` (default) tries to upgrade to HTTP/2, falling back to HTTP/1.1


## Usage examples

### Async requests

By default, hato performs synchronous requests and directly returns a response map.

By providing `async?` option to the request, the request will be performed asynchronously, returning
a [CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html)
of the response map. This can be wrapped in e.g. [manifold](https://github.com/ztellman/manifold),
to give you promise chains etc.

Alternatively, callbacks can be used by passing in `respond` and `raise` functions, in which case
the [CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html)
returned can be used to indicate when processing has completed.

```clojure
; A standard synchronous request
(hc/get "https://httpbin.org/get")

; An async request
(hc/get "https://httpbin.org/get" {:async? true})
; =>
; #object[jdk.internal.net.http.common.MinimalFuture...

; Deref it to get the value
(-> @(hc/get "https://httpbin.org/get" {:async? true})
    :body)
; =>
; { ...some json body }

; Pass in a callback
(hc/get "https://httpbin.org/get"
       { :async? true }
       (fn [resp] (println "Got status" (:status resp)))
       identity)
; =>
; #object[jdk.internal.net.http.common.MinimalFuture...
; Got status 200

(future-done? *1)
; =>
; true

; Exceptional status codes by default will call raise with an ex-info containing the response map.
; This means we can use ex-data to get the data back out.
@(hc/get "https://httpbin.org/status/400" {:async? true} identity #(-> % ex-data :status))
; =>
; 400
```

### Making queries

hato can generate url encoded query strings in multiple ways

```clojure
; Via un url
(hc/get "http://moo.com?hello=world&a=1&a=2" {})

; Via query-params
(hc/get "http://moo.com" {:query-params {:hello "world" :a [1 2]}})

; Values are urlencoded
(hc/get "http://moo.com" {:query-params {:q "a-space and-some-chars$&!"}})
; Generates query: "q=a-space+and-some-chars%24%26%21"

; Nested params are flattened by default
(hc/get "http://moo.com" {:query-params {:a {:b {:c 5} :e {:f 6}}}})
; => "a[b][c]=5&a[e][f]=6", url encoded

; Flattening can be disabled
(hc/get "http://moo.com" {:query-params {:a {:b {:c 5} :e {:f 6}}} :ignore-nested-query-string true})
; => "a={:b {:c 5}, :e {:f 6}}", url encoded
```

Form parameters can also be passed as a map:

```clojure
(hc/post "http://moo.com" {:form-params {:hello "world"}})

; Send a json body "{\"a\": {\"b\": 5}}"
(hc/post "http://moo.com" {:form-params {:a {:b 5}} :content-type :json})

; Nested params are not flattened by default
; Sends a body of "a={:b {:c 5}, :e {:f 6}}", x-www-form-urlencoded
(hc/post "http://moo.com" {:form-params {:a {:b {:c 5} :e {:f 6}}}})

; Flattening can be enabled
; Sends a body of "a[b][c]=5&a[e][f]=6", url encoded
(hc/post "http://moo.com" {:form-params {:a {:b {:c 5} :e {:f 6}}}
                           :flatten-nested-form-params true})
```

As a convenience, nesting can also be controlled by `:flatten-nested-keys`:

```clojure
; Flattens both query and form params
(hc/post "http://moo.com" {... :flatten-nested-keys [:query-params :form-params]})

; Flattens only query params
(hc/post "http://moo.com" {... :flatten-nested-keys [:query-params]})
```


### Output coercion

You can control whether you like hato to return an `InputStream` (using `:as :stream`), `byte-array` (using `:as :byte-array`) or `String` (`:as :string`) with no further coercion.

```clojure
; Returns a string response
(hc/get "http://moo.com" {})

; Returns a byte array
(hc/get "http://moo.com" {:as :byte-array})

; Returns an InputStream
(hc/get "http://moo.com" {:as :stream})

; Coerces clojure strings
(hc/get "http://moo.com" {:as :clojure})

; Coerces transit. Requires optional dependency com.cognitect/transit-clj.
(hc/get "http://moo.com" {:as :transit+json})
(hc/get "http://moo.com" {:as :transit+msgpack})

; Coerces JSON strings into clojure data structure
; Requires optional dependency cheshire
(hc/get "http://moo.com" {:as :json})
(hc/get "http://moo.com" {:as :json-string-keys})

; Coerce responses with exceptional status codes
(hc/get "http://moo.com" {:as :json :coerce :always})
```

By default, hato only coerces JSON responses for unexceptional statuses. Control this with the `:coerce` option:

```clojure
:unexceptional ; default - only coerce response bodies for unexceptional status codes
:exceptional ; only coerce for exceptional status codes
:always ; coerce for any status code
```

### Certificate authentication

Client authentication can be done by passing in an SSLContext:

```clojure
; Directly pass in an SSLContext that you made yourself
(hc/get "https://secure-url.com" {:http-client {:ssl-context SomeSSLContext}})

; Pass in your credentials
(hc/get "https://secure-url.com" {:http-client {:ssl-context {:keystore (io/resource "somepath.p12")
                                                              :keystore-pass "password"
                                                              :trust-store (io/resource "cacerts.p12"
                                                              :trust-store-pass "another-password")}}})
```
If either `:keystore` or `:trust-store` are not provided, the respective system default will be used.

The defaults can be overridden with java options, so the below is equivalent to the above (with the caveat
that the path should be on the filesystem rather than in the jar resources):

```
-Djavax.net.ssl.keyStore=somepath.12
-Djavax.net.ssl.keyStoreType=pkcs12
-Djavax.net.ssl.keyStorePassword=password
-Djavax.net.ssl.trustStore=cacerts.p12
-Djavax.net.ssl.trustStoreType=pkcs12
-Djavax.net.ssl.trustStorePassword=another-password
```

### Redirects

By default, hato does not follow redirects. To change this behaviour, use the `redirect-policy` option.

Implementation notes from the [docs](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.Redirect.html):
> When automatic redirection occurs, the request method of the redirected request may be modified
depending on the specific 30X status code, as specified in [RFC 7231](https://tools.ietf.org/html/rfc7231).
In addition, the 301 and 302 status codes cause a POST request to be converted to a GET in the redirected request.

```clojure
; Always redirect, except from HTTPS URLs to HTTP URLs
(hc/get "http://moo.com" {:http-client {:redirect-policy :normal}})

; Always redirect
(hc/get "http://moo.com" {:http-client {:redirect-policy :always}})
```

The Java HttpClient does not provide a direct option for max redirects. By default, it is 5.
To change this, set the java option to e.g. `-Djdk.httpclient.redirects.retrylimit=10`.

The client does not throw an exception if the retry limit has been breached. Instead,
it will return a response with the redirect status code (30x) and empty body.

### Multipart Requests

To send a multipart request, `:multipart` may be supplied as a sequence of maps as described in
[request options](#request-options). This will add the appropriate Content-Type header as well as replace
the `:body` of the request with an `InputStream` of the supplied parts.

```clojure
(hc/post "http://moo.com"
        {:multipart [{:name "title" :content "My Awesome Picture"}
                     {:name "Content/type" :content "image/jpeg"}
                     {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                     {:name "file" :content (io/file ".test-data")}
                     {:name "data" :content (.getBytes "hi" "UTF-8") :content-type "text/plain" :file-name "data.txt"}
                     {:name "jsonParam" :content (io/file ".test-data") :content-type "application/json" :file-name "data.json"}]})
```


### Custom middleware

hato has a stack of middleware that it applies by default if you use the built in request function. You can
supply different middleware by using `wrap-request` yourself:

```clojure
; Using the default middleware
(hc/request {:url "https://httpbin.org/get" :method :get})

; With convenience method
(hc/get "https://httpbin.org/get")

; Let's write an access log middleware

; Define a new middleware
(defn log-and-return
    [resp]
    (println :access-log (:uri resp) (:status resp) (:request-time resp))
    resp)

(defn wrap-log
  [client]
  (fn
    ([req]
     (let [resp (client req)]
       (log-and-return resp)))
    ([req respond raise]
     (client req
             #(respond (log-and-return %))
             raise))))

; Create your own middleware stack.
; Note that ordering is important here:
; - After wrap-request-timing so :request-time is available on the response
; - Before wrap-exceptions so that exceptional responses have not yet caused an exception to be thrown
(def my-middleware (concat [(first hm/default-middleware) wrap-log] (drop 1 hm/default-middleware)))

; Now it logs
(request {:url "https://httpbin.org/get" :method :get :middleware my-middleware})
; :access-log https://httpbin.org/get 200 1069
; => Returns response map

(request {:url "https://httpbin.org/status/404" :method :get :middleware my-middleware})
; :access-log https://httpbin.org/status/404 404 1924
; ...Throws some ExceptionInfo

(get "https://httpbin.org/get" {:middleware my-middleware})
; :access-log https://httpbin.org/get 200 1069
; => Returns string body
```

### WebSockets

The simplest way to get started is with the `websocket` function:

```clojure
(require '[hato.websocket :as ws])

(let [ws @(ws/websocket "ws://echo.websocket.org"
                        {:on-message (fn [ws msg last?]
                                       (println "Received message:" msg))
                         :on-close   (fn [ws status reason]
                                       (println "WebSocket closed!"))})]
  (ws/send! ws "Hello World!")
  (Thread/sleep 1000)
  (ws/close! ws))
```

By default, hato WebSocket functions are asynchronous and most return a
[CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html). This
can be wrapped in e.g. [manifold](https://github.com/ztellman/manifold), to give you promise chains etc.

```clojure
(require '[hato.websocket :as ws])
(require '[manifold.deferred :as d])

(-> (ws/websocket "ws://echo.websocket.org"
                  {:on-message (fn [ws msg last?]
                                 (println "Received message:" msg))
                   :on-close   (fn [ws status reason]
                                 (println "WebSocket closed!"))})
    (d/chain #(ws/send! % "Hello")
             #(ws/send! % "World!")
             #(ws/close! %))
    (d/catch Exception #(println "Something went wrong!" %)))
```

### WebSocket options

`uri` A WebSocket uri (e.g. `"ws://echo.websocket.org"`).


`opts` Additional options may be a map of any of the following keys:

  - `:http-client` An `HttpClient` (e.g. created by `hato.client/build-http-client`). If not provided, a default client will be used.
  - `:headers` Adds the given name-value pair to the list of additional HTTP headers sent during the opening handshake.
  - `:connect-timeout` Sets a timeout for establishing a WebSocket connection, in milliseconds.
  - `:subprotocols` Sets a request for the given subprotocols.
  - `:listener` A WebSocket listener. If a `WebSocket$Listener` is provided, it will be used directly.
  Otherwise one will be created from any handlers (`on-<event>`) passed into the options map.

  - `:on-open` Called when a `WebSocket` has been connected. Called with the WebSocket instance.
  - `:on-message` A textual/binary data has been received. Called with the WebSocket instance, the data, and whether this invocation completes the message.
  - `:on-ping` A Ping message has been received. Called with the WebSocket instance and the ping message.
  - `:on-pong` A Pong message has been received. Called with the WebSocket instance and the pong message.
  - `:on-close` Receives a Close message indicating the WebSocket's input has been closed. Called with the WebSocket instance, the status code, and the reason.
  - `:on-error` An error has occurred. Called with the WebSocket instance and the error.


### Debugging

To view the logs of the Java client, add the java option `-Djdk.httpclient.HttpClient.log=all`.
In Leinengen, this can be done using `:jvm-opts` in `project.clj`.

### Other advanced options

```
# Default keep alive for connection pool is 1200 seconds
-Djdk.httpclient.keepalivetimeout=1200

# Default connection pool size is 0 (unbounded)
-Djdk.httpclient.connectionPoolSize=0
```

## License

Released under the MIT License: http://www.opensource.org/licenses/mit-license.php
