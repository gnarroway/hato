(ns hato.websocket
  (:require [hato.client :as client]
            [manifold.deferred :as d]
            [manifold.stream :as s])
  (:import (java.net.http WebSocket$Listener WebSocket$Builder HttpClient WebSocket)
           (java.time Duration)
           (java.net URI)
           (java.util.concurrent CompletableFuture)
           (java.nio ByteBuffer)))

(def default-websocket-listener
  (reify WebSocket$Listener))

(defn request->WebSocketListener
  "Constructs a new WebSocket listener to receive events for a given WebSocket connection.
   :on-open    Called when a `WebSocket` has been connected.
   :on-text    A textual data has been received.
   :on-binary  A binary data has been received.
   :on-ping    A Ping message has been received.
   :on-pong    A Pong message has been received.
   :on-pong    Receives a Close message indicating the WebSocket's input has been closed.
   :on-error   An error has occurred."
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

(defn ^CompletableFuture websocket-raw*
  "Constructs a new WebSocket connection."
  [{:keys [http-client listener]
    :as   req}]
  (let [^HttpClient http-client (if (instance? HttpClient http-client) http-client (client/build-http-client http-client))
        ^WebSocket$Listener listener (if (instance? WebSocket$Listener listener) listener (request->WebSocketListener listener))]
    (request->WebSocket http-client listener req)))

(defn ^CompletableFuture websocket-raw
  "Constructs a new WebSocket connection."
  [uri listener & [opts]]
  (websocket-raw* (merge {:uri uri :listener listener} opts)))

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

(defn websocket-manifold
  "Constructs a lower level WebSocket duplex manifold stream. The stream
  can be used to send and receive all WebSocket event types."
  [uri out-fn in-fn & [opts]]
  (let [out (s/stream)
        in (s/stream)]
    (-> (websocket-raw uri
                       {:on-close  (fn [ws status reason]
                                     (s/close! in))
                        :on-text   (fn [ws data last?]
                                     (out-fn out {:ws    ws
                                                  :type  :text
                                                  :msg   data
                                                  :last? last?}))
                        :on-binary (fn [ws data last?]
                                     (out-fn out {:ws    ws
                                                  :type  :binary
                                                  :msg   data
                                                  :last? last?}))
                        :on-ping   (fn [ws msg]
                                     (out-fn out {:ws   ws
                                                  :type :ping
                                                  :msg  msg}))
                        :on-pong   (fn [ws msg]
                                     (out-fn out {:ws   ws
                                                  :type :pong
                                                  :msg  msg}))
                        :on-error  (fn [ws err]
                                     (s/put! out (d/error-deferred err))
                                     (s/close! in))}
                       opts)
        (d/chain
          (fn [ws]
            (s/on-closed in #(close! ws))
            (-> (s/mapcat (partial in-fn ws) in)
                (s/connect out))))
        (d/catch Exception
          (fn [err]
            (s/put! out (d/error-deferred err))
            (s/close! in))))
    (s/splice in out)))

(defn websocket-with-events
  "Constructs a lower level WebSocket duplex manifold stream. The stream
  can be used to send and receive all WebSocket event types.

  Messages from the server will be emitted to the stream returned by
  this function as a map of the following:
  :ws    WebSocket
  :type  :text/:binary/:ping/:pong
  :msg   Either CharSequence (:text), ByteBuffer (:binary/:ping/:pong)
  :last? Present for :text/:binary events

  Messages can be sent to the server by putting a map onto the stream
  with the following structure:
  :type  :text/:binary/:ping/:pong
  :msg   Either CharSequence (:text), ByteBuffer (:binary/:ping/:pong)
  :last? Present for :text/:binary events"
  [uri & [opts]]
  (websocket-manifold
    uri
    (fn [stream payload]
      (s/put! stream payload))
    (fn [ws {:keys [type msg last? status-code reason]}]
      (case type
        :text (send-text! ws msg (if (some? last?) last? true))
        :binary (send-binary! ws
                              (if (bytes? msg) (ByteBuffer/wrap msg) msg)
                              (if (some? last?) last? true))
        :ping (send-ping! ws (or msg (ByteBuffer/allocate 0)))
        :pong (send-pong! ws (or msg (ByteBuffer/allocate 0)))
        :close (close! ws
                       (or status-code WebSocket/NORMAL_CLOSURE)
                       (or reason "ok")))
      nil)
    opts))

(defn websocket
  "Constructs a higher level WebSocket duplex manifold stream. The stream
  can be used to send and receive text/binary data.

  Messages from the server will be emitted to the stream returned by
  this function as either a CharSequence (text) or ByteBuffer (binary)

  Messages can be sent to the server by putting either a
  CharSequence (text) or bytes/ByteBuffer (binary) on the stream.

  Note, if you need lower level events like ping/pong, see websocket-with-events."
  [uri & [opts]]
  (websocket-manifold
    uri
    (fn [stream {:keys [type msg]}]
      (when (or (= type :text)
                (= type :binary))
        (s/put! stream msg)))
    (fn [ws msg]
      (cond
        (instance? CharSequence msg)
        (send-text! ws msg true)

        (bytes? msg)
        (send-binary! ws (ByteBuffer/wrap msg) true)

        (instance? msg ByteBuffer)
        (send-binary! ws msg true)

        :else
        (throw (ex-info "Unknown message type" {:msg msg})))
      nil)
    opts))

(defn consume-msgs
  "Helper function to consume all messages from a stream by calling f on each value. Will
  invoke done-cb with no arguments when the websocket closes and will invoke error-cb if
  the websocket receives an error. The only reason to use this over simply s/consume or
  s/consume-async is to handle or report errors more easily."
  ([stream f]
   (consume-msgs stream f nil))
  ([stream f done-cb]
   (consume-msgs stream f done-cb nil))
  ([stream f done-cb error-cb]
   (d/loop []
     (-> (s/take! stream ::drained)
         (d/chain
           (fn [msg]
             (if (identical? ::drained msg)
               ::drained
               (f msg)))
           (fn [result]
             (if (identical? ::drained result)
               (when done-cb
                 (done-cb))
               (d/recur))))
         (d/catch (fn [err]
                    (when error-cb
                      (error-cb err))))))))(extend-type Datafiable
(extend-protocol clojure.core.protocols/Datafiable
  WebSocket
  (datafy [^WebSocket ws]
    {:subprotocol    (.getSubprotocol ws)
     :output-closed? (.isOutputClosed ws)
     :input-closed?  (.isInputClosed ws)}))
