# hato

[![Clojars Project](https://img.shields.io/clojars/v/hato.svg)](https://clojars.org/hato)

[![CircleCI](https://circleci.com/gh/gnarroway/hato.svg?style=svg)](https://circleci.com/gh/gnarroway/hato)

An HTTP client for Clojure, wrapping JDK 11's [HttpClient](https://openjdk.java.net/groups/net/httpclient/intro.html).

It supports both HTTP/1.1 and HTTP/2, with synchronous and asynchronous execution modes.

In general, it will feel familiar to users of http clients like [clj-http](https://github.com/dakrone/clj-http).
The API is designed to be idiomatic and to make common tasks convenient, whilst
still allowing the underlying HttpClient to be configured via native Java objects.

## Status

hato is under active development. Once it has stabilized to a reasonable degree, a release will be published to clojars.

## Installation

hato requires JDK 11 and above. If you are running an older vesion of Java, please look at [clj-http](https://github.com/dakrone/clj-http).

For Leinengen, add this to your project.clj

```clojure
[hato "0.1.0-SNAPSHOT"]
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

Generally, you want to make a reusable client first. This will give you nice things like 
persistent connections and connection pooling.

This can be done with `build-http-client`:

```clojure
; Build the client
(def c (hato/build-http-client {:connect-timeout 10000
                                :follow-redirects :always}))

; Use it for multiple requests
(hc/get "https://httpbin.org/get" {:http-client c})
(hc/get "https://httpbin.org/get?again" {:http-client c})
```

#### build-http-client options

`authenticator` Used for non-preemptive basic authentication. See the `basic-auth` request
  option for pre-emptive authentication. Accepts:

 - A map of `{:user "username" :pass "password"}`
 - a [`java.net.Authenticator`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/Authenticator.html)

`cookie-handler` a [`java.net.CookieHandler`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookieHandler.html)
  if you need full control of your cookies. See `cookie-policy` for a more convenient option.

`cookie-policy` Can be used to construct a [`java.net.CookieManager`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookieManager.html)
  (a type of CookieHandler). However, the `cookie-handler` option will take precedence if it is set. 
  If an invalid option is provided, a CookieManager with the default policy (original-server) 
  will be created. Valid options:

 - `:none` Accepts no cookies
 - `:all`  Accepts all cookies
 - `:original-server` (default) Accepts cookies from original server
 - An implementation of [`java.net.CookiePolicy`](https://docs.oracle.com/javase/7/docs/api/java/net/CookiePolicy.html).

`connect-timeout` Timeout to making a connection, in milliseconds (default: unlimited).

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
(hc/request {:method :get, :url "http://moo.com"})

; Convenience wrappers
(hc/get "http://moo.com" {})
(hc/post "http://moo.com" {:body "cow"})
```

#### request options

`method`Lowercase keyword corresponding to a HTTP request method, such as :get or :post. 

`url` An absolute url to the requested resource (e.g. "http://moo.com/api/1").
 
`accept` Sets the `accept` header. a keyword (e.g. `:json`, for any application/* type) or string (e.g. `"text/html"`) for anything else. 
  
`accept-encoding` List of string/keywords (e.g. `[:gzip]`). By default, "gzip, deflate" will be concatenated
  unless `decompress-body?` is false.

`content-type` a keyword (e.g. `:json`, for any application/* type) or string (e.g. "text/html") for anything else. 
  Sets the appropriate header.
  
`body` the body of the request. This should be a string, byte array, or input stream. Note that clojure data
  is not automatically coerced to string e.g. sending a json body will require generating
  a json string via [cheshire](https://github.com/dakrone/cheshire) or other means.
  
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
  
`query-params` A map of options to turn into a query string. See usage examples for details.

`multi-param-style` Decides how to represent array values when converting `query-params` into a query string. Accepts:
  
  - When unset (default), a repeating parameter `a=1&a=2&a=3`
  - `:array`, a repeating param with array suffix: `a[]=1&a[]=2&a[]=3`
  - `:index`, a repeating param with array suffix and index: `a[0]=1&a[1]=2&a[2]=3`

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

### Certificate authentication

Client authentication can be done by passing in an SSLContext:

```clojure
; Pass in your credentials
(hc/get "https://secure-url.com" {:http-client {:ssl-context {:keystore (io/resource "somepath.p12") 
                                                              :keystore-pass "password"
                                                              :trust-store (io/resource "cacerts.p12"
                                                              :trust-store-pass "another-password")}}})                                             

; Directly pass in an SSLContext that you made yourself
(hc/get "https://secure-url.com" {:http-client {:ssl-context SomeSSLContext}})
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
