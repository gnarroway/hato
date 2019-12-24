(ns hato.websocket
  (:require [hato.client :as client])
  (:import (java.net.http WebSocket$Listener WebSocket$Builder HttpClient WebSocket)
           (java.time Duration)
           (java.net URI)
           (java.util.concurrent CompletableFuture)
           (java.nio ByteBuffer)))

(def default-websocket-listener
  (reify WebSocket$Listener))

(defn request->WebSocketListener
  "Constructs a new WebSocket listener to receive events for a given WebSocket connection.
  :on-open    Called when a `WebSocket` has been connected. Called with the WebSocket instance.
  :on-text    A textual data has been received. Called with the WebSocket instance, the data, and whether this invocation completes the message.
  :on-binary  A binary data has been received. Called with the WebSocket instance, the data, and whether this invocation completes the message.
  :on-message A textual/binary data has been received. Called with the WebSocket instance, the data, and whether this invocation completes the message.
  :on-ping    A Ping message has been received. Called with the WebSocket instance and the ping message.
  :on-pong    A Pong message has been received. Called with the WebSocket instance and the pong message.
  :on-close   Receives a Close message indicating the WebSocket's input has been closed. Called with the WebSocket instance, the status code, and the reason.
  :on-error   An error has occurred. Called with the WebSocket instance and the error."
  [{:keys [on-open
           on-text
           on-binary
           on-message
           on-ping
           on-pong
           on-close
           on-error]}]
  (reify WebSocket$Listener
    (onOpen [_ ws]
      (when on-open
        (on-open ws))
      (.onOpen default-websocket-listener ws))
    (onText [_ ws data last?]
      (when on-text
        (on-text ws data last?))
      (when on-message
        (on-message ws data last?))
      (.onText default-websocket-listener ws data last?))
    (onBinary [_ ws data last?]
      (when on-binary
        (on-binary ws data last?))
      (when on-message
        (on-message ws data last?))
      (.onBinary default-websocket-listener ws data last?))
    (onPing [_ ws msg]
      (when on-ping
        (on-ping ws msg))
      (.onPing default-websocket-listener ws msg))
    (onPong [_ ws msg]
      (when on-pong
        (on-pong ws msg))
      (.onPong default-websocket-listener ws msg))
    (onClose [_ ws status reason]
      (when on-close
        (on-close ws status reason))
      (.onClose default-websocket-listener ws status reason))
    (onError [_ ws err]
      (when on-error
        (on-error ws err))
      (.onError default-websocket-listener ws err))))

(defn ^CompletableFuture request->WebSocket
  "Builds a new WebSocket connection from a request object and returns a future connection.
  Optionally accepts:
  :headers         Adds the given name-value pair to the list of additional
                   HTTP headers sent during the opening handshake.
  :connect-timeout Sets a timeout for establishing a WebSocket connection (in millis).
  :subprotocols    Sets a request for the given subprotocols."
  [^HttpClient http-client
   ^WebSocket$Listener listener
   {:keys [uri
           headers
           connect-timeout
           subprotocols]}]
  (let [^WebSocket$Builder builder (.newWebSocketBuilder http-client)]
    (doseq [[header-n header-v] headers]
      (.header builder header-n header-v))

    (when connect-timeout
      (.connectTimeout builder (Duration/ofMillis connect-timeout)))

    (when (seq subprotocols)
      (.subprotocols builder (first subprotocols) (into-array String (rest subprotocols))))

    (.buildAsync builder (URI/create uri) listener)))

(defn ^CompletableFuture websocket*
  "Constructs a new WebSocket connection."
  [{:keys [http-client listener]
    :as   req}]
  (let [^HttpClient http-client (if (instance? HttpClient http-client) http-client (client/build-http-client http-client))
        ^WebSocket$Listener listener (if (instance? WebSocket$Listener listener) listener (request->WebSocketListener listener))]
    (request->WebSocket http-client listener req)))

(defn ^CompletableFuture websocket
  "Constructs a new WebSocket connection."
  [uri listener & [opts]]
  (websocket* (merge {:uri uri :listener listener} opts)))

(defn ^CompletableFuture send-text!
  "Sends textual data with characters from the given character sequence."
  ([^WebSocket ws ^CharSequence data]
   (send-text! ws data true))
  ([^WebSocket ws ^CharSequence data last?]
   (.sendText ws data last?)))

(defn ^CompletableFuture send-binary!
  "Sends binary data with bytes from the given buffer."
  ([^WebSocket ws ^ByteBuffer data]
   (send-binary! ws data true))
  ([^WebSocket ws ^ByteBuffer data last?]
   (.sendBinary ws data last?)))

(defprotocol Sendable
  "Protocol to represent sendable message types for a WebSocket. Useful for custom
  extensions."
  (do-send! [msg last? ^WebSocket ws]))

(extend-protocol Sendable
  (Class/forName "[B")
  (do-send! [^bytes msg last? ^WebSocket ws]
    (send-binary! ws (ByteBuffer/wrap msg) last?))

  ByteBuffer
  (do-send! [^ByteBuffer msg last? ^WebSocket ws]
    (send-binary! ws msg last?))

  CharSequence
  (do-send! [^CharSequence msg last? ^WebSocket ws]
    (send-text! ws msg last?)))

(defn send!
  "Sends a message to the WebSocket"
  ([^WebSocket ws msg]
   (send! ws msg true))
  ([^WebSocket ws msg last?]
   (do-send! msg last? ws)))

(defn ^CompletableFuture ping!
  "Sends a Ping message with bytes from the given buffer."
  ([^WebSocket ws]
   (ping! ws (ByteBuffer/allocate 0)))
  ([^WebSocket ws ^ByteBuffer msg]
   (.sendPing ws msg)))

(defn ^CompletableFuture pong!
  "Sends a Pong message with bytes from the given buffer."
  ([^WebSocket ws]
   (pong! ws (ByteBuffer/allocate 0)))
  ([^WebSocket ws ^ByteBuffer msg]
   (.sendPong ws msg)))

(defn ^CompletableFuture close!
  "Initiates an orderly closure of this WebSocket's output by sending a
  Close message with the given status code and the reason."
  ([^WebSocket ws]
   (.sendClose ws WebSocket/NORMAL_CLOSURE "ok"))
  ([^WebSocket ws status-code ^String reason]
   (.sendClose ws status-code reason)))

(defn request
  "Increments the counter of invocations of receive methods."
  [^WebSocket ws ^long n]
  (.request ws n))

(defn abort!
  "Closes this WebSocket's input and output abruptly."
  [^WebSocket ws]
  (.abort ws))

(extend-protocol clojure.core.protocols/Datafiable
  WebSocket
  (datafy [^WebSocket ws]
    {:subprotocol    (.getSubprotocol ws)
     :output-closed? (.isOutputClosed ws)
     :input-closed?  (.isInputClosed ws)}))