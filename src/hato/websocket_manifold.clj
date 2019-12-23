(ns hato.websocket-manifold
  (:require [hato.websocket :as websocket]
            [manifold.stream :as s]
            [manifold.deferred :as d])
  (:import (java.nio ByteBuffer)
           (java.net.http WebSocket)))

(defn websocket-manifold
  "Constructs a lower level WebSocket duplex manifold stream. The stream
  can be used to send and receive all WebSocket event types."
  [uri out-fn in-fn & [opts]]
  (let [out (s/stream)
        in (s/stream)]
    (-> (websocket/websocket uri
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
            (s/on-closed in #(websocket/close! ws))
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
        :text (websocket/send-text! ws msg (if (some? last?) last? true))
        :binary (websocket/send-binary! ws
                                        (if (bytes? msg) (ByteBuffer/wrap msg) msg)
                                        (if (some? last?) last? true))
        :ping (websocket/ping! ws (or msg (ByteBuffer/allocate 0)))
        :pong (websocket/pong! ws (or msg (ByteBuffer/allocate 0)))
        :close (websocket/close! ws
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
        (websocket/send-text! ws msg true)

        (bytes? msg)
        (websocket/send-binary! ws (ByteBuffer/wrap msg) true)

        (instance? msg ByteBuffer)
        (websocket/send-binary! ws msg true)

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
                      (error-cb err))))))))
