# hato

An HTTP client for Clojure, wrapping JDK 11's [HttpClient](https://openjdk.java.net/groups/net/httpclient/intro.html).

It supports both HTTP/1.1 and HTTP/2, with synchronous and asynchronous execution models.

In general, it will feel familiar to users of ring-style http clients like [clj-http](https://github.com/dakrone/clj-http).
The API is designed to be idiomatic and to make common tasks convenient, whilst
still allowing the underlying HttpClient to be configured via native Java objects.

## Status

hato is under active development. Once it has stabilized to a reasonable degree, it will be published to clojars.

## Installation

hato requires JDK 11 or above. If you are running an older Java, please look at [clj-http](https://github.com/dakrone/clj-http).

For Leinengen, add this to your project.clj

```clojure
[hato "TBD"]
```

## Quickstart

The main client is available in `hato.client`.

Require it to get started and make a request:

```clojure

(ns my.app
  (:require [hato.client :as hc]))
  
  (hc/get "https://httpbin.org/get" {})
  ; =>
  ; {:request-time 112
  ;  :status 200
  ;  :body "{\"url\" ...}"
  ;  ...}
```

## Usage

### Building a client

Generally, you want to make a reusable client first. This can be done with `build-http-client`:

```clojure
; Build the client
(def c (hato/build-http-client {:connect-timeout 10000
                                :follow-redirects :always}))

; Use it for multiple requests
(hc/get "http://moo.com" {:http-client c})
(hc/get "http://cow.com" {:http-client c})
```

#### build-http-client options

`authenticator` Used for non-preemptive basic authentication. In general, 
  you should just use the `basic-auth` per-request option which performs
  pre-emptive authentication. This is here to expose the full HttpClient API. Accepts:

 - A map of `{:user "username" :pass "password"}`
 - a [`java.net.Authenticator`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/Authenticator.html)

`cookie-handler` a [`java.net.CookieHandler`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookieHandler.html)
  if you need full control of your cookies. See `cookie-policy` for a more convenient option.

`cookie-policy` Can be used to construct a [`java.net.CookieManager`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookieManager.html)
  (a type of CookieHandler). However, the `cookie-handler` option will take precedence if it is set. 
  Furthermore, any invalid option will still create a CookieManager
  with the default policy (original-server) unaffected. Valid options:

 - `:none` Accepts no cookies
 - `:all`  Accepts all cookies
 - `:original-server` (default) Accepts cookies from original server
 - An implementation of [`java.net.CookiePolicy`](https://docs.oracle.com/javase/7/docs/api/java/net/CookiePolicy.html).

`connect-timeout` Timeout to making a connection, in milliseconds

`redirect-policy` Sets the redirect policy.

  - :never (default) Never follow redirects.
  - :normal Always redirect, except from HTTPS URLs to HTTP URLs. 
  - :always Always redirect

`priority` an integer between 1 and 256 (both inclusive) for HTTP/2 requests

`proxy` Sets a proxy selector. If not set, uses the default system-wide ProxySelector,
  which cane be configured by JVM opts such as `-Dhttp.proxyHost=somehost` and `-Dhttp.proxyPort=80` 
  (see [all options](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/doc-files/net-properties.html#Proxies)).
  Also accepts:
  
  - `:no-proxy` to explicitly disable the default behavior, implying a direct connection; or
  - a [`java.net.ProxySelector`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/ProxySelector.html)

`ssl-context` an javax.net.ssl.SSLContext

`ssl-parameters` a javax.net.ssl.SSLParameters

`version` Sets preferred HTTP protocol version.
  - `:http-1.1` prefer HTTP/1.1
  - `:http-2` (default) tries to upgrade to HTTP/2, falling back to HTTP/1.1

### Making requests

The core function for making requests is `hato.client/request`, which takes a [ring](https://github.com/ring-clojure/ring/blob/master/SPEC)
request and returns a response. Convenience wrappers are provided for the http verbs (`get`, `post`, `put` etc.).

```clojure
; The main request function
(hc/request {:method :get, :url "http://moo.com"})

; Convenience wrappers
(hc/get "http://moo.com" {})
(hc/post "http://moo.com" {:body "cow"})
```

#### request options

`method`Lowercase keyword corresponding to a HTTP request method, such as :get or :post. 

`url` A full url to the requested resource (e.g. "http://user:pass@moo.com/api?q=1"). For historical reasons,
  `uri` cannot be used in the same manner.
 
`accept` Sets the `accept` header. a keyword (e.g. `:json`) (for any application/* type) or string (e.g. `"text/html"`) for anything else. 
  
`accept-encoding` List of string/keywords (e.g. `[:gzip]`). By default, "gzip, deflate" will be concatenated
  unless `decompress-body?` is false.

`content-type` a keyword :json (for any application/* type) or string (e.g. "text/html") for anything else. 
  Sets the appropriate header.
  
`body` the body of the request. This should be a string, byte array, or input stream. Note that input
  coercion is not provided so e.g. sending a json body will require transforming a clojure data structure
  into a json string via [cheshire](https://github.com/dakrone/cheshire) or other means.
  
`as` Return response body in a certain format. Valid options:

  - Return an object type: `:string` (default), `:byte-array`, `:stream`, `:discarding`,
  - Coerce response body from a certain format: `:json`, `:json-string-keys`,
  `:json-strict`, `:json-strict-string-keys`, `:clojure`, `:transit+json`, `:transit+msgpack`. JSON and transit
  coercion require optional dependencies [cheshire](https://github.com/dakrone/cheshire) and
  [com.cognitect/transit-clj](https://github.com/cognitect/transit-clj) to be installed, respectively.
  - A [`java.net.http.HttpRequest$BodyHandler`](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpResponse.BodyHandler.html).
  Note that decompression is enabled by default but only handled for the options above. A custom BodyHandler
  may require opting out of compression, or implementing a multimethod specific to the handler. 
  

`coerce` Determine which status codes to coerce response bodies. `:unexceptional` (default), `:always`, `:exceptional`. 
  This presently only has an effect for json coercions.

`headers` Map of lower case strings to header values, concatenated with ',' when multiple values for a key.
  This is presently a slight incompatibility with clj-http, which accepts keyword keys and list values.

`basic-auth` Sets `Basic` authorization header, `"user:pass"` or `["user" "pass"]`. 

`oauth-token` String, will set `Bearer` authorization header

`decompress-body?` By default, sets request header to accept "gzip, deflate" encoding, and decompresses the response.
  Set to `false` to turn off this behaviour.
 
`throw-exceptions?` By default, the client will throw exceptions for exceptional response statuses. Set this to
  `false` to return the response without throwing.
  
`async?` Boolean, defaults to false. See below section on async requests.
 
---

##### Options specific to underlying implementation.

`http-client` An `HttpClient` created by `build-http-client` or other means. If not provided, all options passed
  to request will be passed through to `build-http-client`. i.e. `request` accepts all options detailed in the 
  above `build-http-client` section.

`expect-continue` Requests the server to acknowledge the request before sending the body. This is disabled by default. 

`timeout` Timeout to receiving a response, in milliseconds

`version` Sets preferred HTTP protocol version per request.

  - `:http-1.1` prefer HTTP/1.1
  - `:http-2` (default) tries to upgrade to HTTP/2, falling back to HTTP/1.1

---

##### Other options

The following options exist for compatibility with the ring spec. In general, the core options
 above will be transformed into them via middleware, and as such, these are just documented for completeness.
 
`scheme` The transport protocol, :http or :https

`server-name` hostname e.g. google.com

`uri` The resource excluding query string and '?', starting with '/'. Note that for historical reasons,
this is not the same as the full `url`.

`server-port` Integer

`query-string` Query string, if present

`request-method` Same as `method`. Exists to be like ring.


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
(hc/get "http://moo.com" {})

; An async request
(hc/get "http://moo.com" {:async? true})
; =>
; #object[jdk.internal.net.http.common.MinimalFuture...

; Deref it to get the value87
(-> @(hc/get "https://httpbin.org/get" {:as :json :async? true})
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
```

### Output coercion

hato performs output coercion of the response body, returning a string by default.

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
(hc/get "http://moo.com" {:as :json-strict})
(hc/get "http://moo.com" {:as :json-string-keys})
(hc/get "http://moo.com" {:as :json-strict-string-keys})

; Coerce responses with exceptional status codes
(hc/get "http://moo.com" {:as :json :coerce :always})
```

By default, hato only coerces JSON responses for unexceptional statuses. Control this with the `:coerce` option:

```clojure
:unexceptional ; default - only coerce response bodies for unexceptional status codes
:exceptional ; only coerce for exceptional status codes
:always ; coerce for any status code
```

## License

Released under the MIT License: http://www.opensource.org/licenses/mit-license.php
