(ns hato.websocket
  (:import (java.net.http WebSocket$Listener WebSocket$Builder HttpClient WebSocket)
           (java.time Duration)
           (java.net URI)
           (java.util.concurrent CompletableFuture)
           (java.nio ByteBuffer)
           (java.util.function Function)))

(set! *warn-on-reflection* true)

(defn request->WebSocketListener
  "Constructs a new WebSocket listener to receive events for a given WebSocket connection.

  Takes a map of:

  - `:on-open`    Called when a `WebSocket` has been connected. Called with the WebSocket instance.
  - `:on-message` A textual/binary data has been received. Called with the WebSocket instance, the data, and whether this invocation completes the message.
  - `:on-ping`    A Ping message has been received. Called with the WebSocket instance and the ping message.
  - `:on-pong`    A Pong message has been received. Called with the WebSocket instance and the pong message.
  - `:on-close`   Receives a Close message indicating the WebSocket's input has been closed. Called with the WebSocket instance, the status code, and the reason.
  - `:on-error`   An error has occurred. Called with the WebSocket instance and the error."
  [{:keys [on-open
           on-message
           on-ping
           on-pong
           on-close
           on-error]}]
  ; The .requests below is from the implementation of the default listener
  (reify WebSocket$Listener
    (onOpen [_ ws]
      (.request ws 1)
      (when on-open
        (on-open ws)))
    (onText [_ ws data last?]
      (.request ws 1)
      (when on-message
        (.thenApply (CompletableFuture/completedFuture nil)
                    (reify Function
                      (apply [_ _] (on-message ws data last?))))))
    (onBinary [_ ws data last?]
      (.request ws 1)
      (when on-message
        (.thenApply (CompletableFuture/completedFuture nil)
                    (reify Function
                      (apply [_ _] (on-message ws data last?))))))
    (onPing [_ ws data]
      (.request ws 1)
      (when on-ping
        (.thenApply (CompletableFuture/completedFuture nil)
                    (reify Function
                      (apply [_ _] (on-ping ws data))))))
    (onPong [_ ws data]
      (.request ws 1)
      (when on-pong
        (.thenApply (CompletableFuture/completedFuture nil)
                    (reify Function
                      (apply [_ _] (on-pong ws data))))))
    (onClose [_ ws status reason]
      (when on-close
        (.thenApply (CompletableFuture/completedFuture nil)
                    (reify Function
                      (apply [_ _] (on-close ws status reason))))))
    (onError [_ ws err]
      (when on-error
        (on-error ws err)))))

(defn websocket*
  "Same as `websocket` but take all arguments as a single map"
  [{:keys [uri
           listener
           http-client
           headers
           connect-timeout
           subprotocols]
    :as opts}]
  (let [^HttpClient http-client (if (instance? HttpClient http-client) http-client (HttpClient/newHttpClient))
        ^WebSocket$Listener listener (if (instance? WebSocket$Listener listener) listener (request->WebSocketListener opts))]

    (let [^WebSocket$Builder builder (.newWebSocketBuilder http-client)]
      (doseq [[header-n header-v] headers]
        (.header builder header-n header-v))

      (when connect-timeout
        (.connectTimeout builder (Duration/ofMillis connect-timeout)))

      (when (seq subprotocols)
        (.subprotocols builder (first subprotocols) (into-array String (rest subprotocols))))

      (.buildAsync builder (URI/create uri) listener))))

(defn websocket
  "Builds a new WebSocket connection from a request object and returns a future connection.

  Arguments:

  - `uri` a websocket uri
  - `opts` (optional), a map of:
    - `:http-client` An HttpClient - will use a default HttpClient if not provided
    - `:listener` A WebSocket$Listener - alternatively will be created from the handlers passed into opts:
                  :on-open, :on-message, :on-ping, :on-pong, :on-close, :on-error
    - `:headers` Adds the given name-value pair to the list of additional
                 HTTP headers sent during the opening handshake.
    - `:connect-timeout` Sets a timeout for establishing a WebSocket connection (in millis).
    - `:subprotocols` Sets a request for the given subprotocols."
  [uri opts]
  (websocket* (assoc opts :uri uri)))

(defprotocol Sendable
  "Protocol to represent sendable message types for a WebSocket. Useful for custom extensions."
  (-send! [data last? ws]))

(extend-protocol Sendable
  (Class/forName "[B")
  (-send! [data last? ^WebSocket ws]
    (.sendBinary ws (ByteBuffer/wrap data) last?))

  ByteBuffer
  (-send! [data last? ^WebSocket ws]
    (.sendBinary ws data last?))

  CharSequence
  (-send! [data last? ^WebSocket ws]
    (.sendText ws data last?)))

(defn send!
  "Sends a message to the WebSocket.

  `data` can be a CharSequence (e.g. string) or ByteBuffer"
  ([^WebSocket ws data]
   (send! ws data nil))
  ([^WebSocket ws data {:keys [last?] :or {last? true}}]
   (-send! data last? ws)))

(defn ^CompletableFuture ping!
  "Sends a Ping message with bytes from the given buffer."
  [^WebSocket ws ^ByteBuffer data]
  (.sendPing ws data))

(defn ^CompletableFuture pong!
  "Sends a Pong message with bytes from the given buffer."
  [^WebSocket ws ^ByteBuffer data]
  (.sendPong ws data))

(defn ^CompletableFuture close!
  "Initiates an orderly closure of this WebSocket's output by sending a
  Close message with the given status code and the reason."
  ([^WebSocket ws]
   (close! ws WebSocket/NORMAL_CLOSURE ""))
  ([^WebSocket ws status-code ^String reason]
   (.sendClose ws status-code reason)))

(defn abort!
  "Closes this WebSocket's input and output abruptly."
  [^WebSocket ws]
  (.abort ws))
