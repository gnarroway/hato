(ns hato.websocket
  (:require [hato.client :as client]
            [manifold.deferred :as d])
  (:import (java.net.http WebSocket$Listener WebSocket$Builder HttpClient WebSocket)
           (java.time Duration)
           (java.net URI)
           (java.util.concurrent CompletableFuture)
           (java.nio ByteBuffer)))

(def default-websocket-listener
  (reify WebSocket$Listener))

(defn request->WebSocketListener
  "Constructs a new websocket listener to receive events for a given websocket connection."
  [{:keys [on-open
           on-text
           on-binary
           on-ping
           on-pong
           on-close
           on-error]}]
  (reify WebSocket$Listener
    (onOpen [_ ws]
      (when on-open
        (on-open ws))
      (.onOpen default-websocket-listener ws))
    (onText [_ ws data last]
      (when on-text
        (on-text ws data last))
      (.onText default-websocket-listener ws data last))
    (onBinary [_ ws data last]
      (when on-binary
        (on-binary ws data last))
      (.onBinary default-websocket-listener ws data last))
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
  "Builds a new websocket connection from a request object and returns a future connection."
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

    (when subprotocols
      (.subprotocols builder (first subprotocols) (into-array String (rest subprotocols))))

    (.buildAsync builder (URI/create uri) listener)))

(defn websocket*
  "Constructs a new websocket connection."
  [{:keys [http-client listener]
    :as   req}]
  (let [^HttpClient http-client (if (instance? HttpClient http-client) http-client (client/build-http-client http-client))
        ^WebSocket$Listener listener (if (instance? WebSocket$Listener listener) listener (request->WebSocketListener listener))
        ^CompletableFuture future (request->WebSocket http-client listener req)]
    (d/->deferred future)))

(defn websocket
  "Constructs a new websocket connection."
  [uri listener & [opts]]
  (websocket* (merge {:uri uri :listener listener} opts)))

(defn ^CompletableFuture send-text!
  "Sends textual data with characters from the given character sequence."
  [^WebSocket ws ^CharSequence data last]
  (.sendText ws data last))

(defn ^CompletableFuture send-binary!
  "Sends binary data with bytes from the given buffer."
  [^WebSocket ws ^ByteBuffer data last]
  (.sendBinary ws data last))

(defn ^CompletableFuture send-ping!
  "Sends a Ping message with bytes from the given buffer."
  [^WebSocket ws ^ByteBuffer msg]
  (.sendPing ws msg))

(defn ^CompletableFuture send-pong!
  "Sends a Pong message with bytes from the given buffer."
  [^WebSocket ws ^ByteBuffer msg]
  (.sendPong ws msg))

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

(defn ^String sub-protocol
  "Returns the subprotocol used by this WebSocket."
  [^WebSocket ws]
  (.getSubprotocol ws))

(defn output-closed?
  "Tells whether this WebSocket's output is closed."
  [^WebSocket ws]
  (.isOutputClosed ws))

(defn input-closed?
  "Tells whether this WebSocket's input is closed."
  [^WebSocket ws]
  (.isInputClosed ws))

(defn abort!
  "Closes this WebSocket's input and output abruptly."
  [^WebSocket ws]
  (.abort ws))
