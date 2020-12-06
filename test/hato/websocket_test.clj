(ns hato.websocket-test
  (:require [clojure.test :refer :all]
            [hato.websocket :refer :all]
            [org.httpkit.server :as http-kit])
  (:import (java.nio ByteBuffer)
           (java.net.http WebSocket)
           (java.util.concurrent ExecutionException)))

(defn byte-buffer->byte-array
  "Converts a byte buffer to a byte array."
  ^bytes [^ByteBuffer buf]
  (let [bytes (byte-array (.remaining buf))]
    (.get buf bytes)
    bytes))

(defn ws-handler
  "Fake WebSocket handler with overrides."
  [{:keys [on-receive on-ping on-close init]} req]
  (when init
    (init req))
  (http-kit/with-channel req ch
    (http-kit/on-receive ch #(when on-receive (on-receive ch %)))
    (http-kit/on-ping ch #(when on-ping (on-ping ch %)))
    (http-kit/on-close ch #(when on-close (on-close ch %)))))

(defmacro with-ws-server
  "Spins up a local WebSocket server with http-kit."
  [opts & body]
  `(let [s# (http-kit/run-server (partial ws-handler ~opts) {:port 1234})]
     (try ~@body
          (finally
            (s# :timeout 100)))))

(deftest test-text-messages
  (with-ws-server {:on-receive #(http-kit/send! %1 %2)}
    (testing "echo server sends back response with send!"
      (let [p (promise)
            ws @(websocket "ws://localhost:1234" {:on-message (fn [_ msg _]
                                                                (deliver p (str msg)))})]
        (send! ws "Hello World!")
        (is (= "Hello World!" (deref p 5000 ::timeout)))))

    (testing "echo server sends back response with send!"
      (let [p (promise)
            ws @(websocket "ws://localhost:1234" {:on-message (fn [_ msg _]
                                                                (deliver p (str msg)))})]
        (send! ws "Hello World!")
        (is (= "Hello World!" (deref p 5000 ::timeout)))))

    (testing "can send multiple segments before last is echoed."
      (let [p (promise)
            ws @(websocket "ws://localhost:1234" {:on-message (fn [_ msg _]
                                                                (deliver p (str msg)))})]
        (send! ws "Hello" {:last? false})
        (send! ws " " {:last? false})
        (send! ws "World" {:last? false})
        (send! ws "!")
        (is (= "Hello World!" (deref p 5000 ::timeout)))))))

(deftest test-binary-messages
  (with-ws-server {:on-receive #(http-kit/send! %1 %2)}
    (testing "echo server sends back response with send-binary!"
      (let [p (promise)
            ws @(websocket "ws://localhost:1234" {:on-message (fn [_ msg _]
                                                                (deliver p (String. (byte-buffer->byte-array msg) "UTF-8")))})]
        (send! ws (ByteBuffer/wrap (.getBytes "Hello World!" "UTF-8")))
        (is (= "Hello World!" (deref p 5000 ::timeout)))))

    (testing "echo server sends back response with send! and a ByteBuffer"
      (let [p (promise)
            ws @(websocket "ws://localhost:1234" {:on-message (fn [_ msg _]
                                                                (deliver p (String. (byte-buffer->byte-array msg) "UTF-8")))})]
        (send! ws (ByteBuffer/wrap (.getBytes "Hello World!" "UTF-8")))
        (is (= "Hello World!" (deref p 5000 ::timeout)))))

    (testing "echo server sends back response with send! and a byte array"
      (let [p (promise)
            ws @(websocket "ws://localhost:1234" {:on-message (fn [_ msg _]
                                                                (deliver p (String. (byte-buffer->byte-array msg) "UTF-8")))})]
        (send! ws (.getBytes "Hello World!" "UTF-8"))
        (is (= "Hello World!" (deref p 5000 ::timeout)))))

    (testing "can send multiple segments before last is echoed."
      (let [p (promise)
            ws @(websocket "ws://localhost:1234" {:on-message (fn [_ msg _]
                                                                (deliver p (String. (byte-buffer->byte-array msg) "UTF-8")))})]
        (send! ws (ByteBuffer/wrap (.getBytes "Hello" "UTF-8")) {:last? false})
        (send! ws (ByteBuffer/wrap (.getBytes " " "UTF-8")) {:last? false})
        (send! ws (ByteBuffer/wrap (.getBytes "World" "UTF-8")) {:last? false})
        (send! ws (ByteBuffer/wrap (.getBytes "!" "UTF-8")))
        (is (= "Hello World!" (deref p 5000 ::timeout)))))))

(deftest test-ping-pong
  (testing "server sends back pong on ping request."
    (let [ping-p (promise)
          pong-p (promise)]
      (with-ws-server {:on-ping (fn [_ ^bytes x]
                                  (deliver ping-p (String. x "UTF-8")))}
        (let [ws @(websocket "ws://localhost:1234" {:on-pong (fn [_ msg]
                                                               (deliver pong-p (String. (byte-buffer->byte-array msg) "UTF-8")))})]
          (ping! ws (ByteBuffer/wrap (.getBytes "Hello World!" "UTF-8")))
          (is (= "Hello World!" (deref ping-p 5000 ::timeout)))
          (is (= "Hello World!" (deref pong-p 5000 ::timeout))))))))

(deftest test-open-and-close
  (testing "closing the WebSocket connection invokes correct callbacks"
    (with-ws-server {}
      (let [open-p (promise)
            close-p (promise)
            ws @(websocket "ws://localhost:1234" {:on-open  (fn [_] (deliver open-p true))
                                                  :on-close (fn [_ status reason] (deliver close-p [status reason]))})]
        (close! ws)
        (is (true? (deref open-p 5000 ::timeout)))
        (is (= [WebSocket/NORMAL_CLOSURE ""] (deref close-p 5000 ::timeout)))))))

(deftest test-headers
  (testing "connecting with headers delivers them to the server"
    (let [init-p (promise)
          headers {"x-foo" "Hello"
                   "x-bar" "World"}]
      (with-ws-server {:init #(deliver init-p (select-keys (:headers %) (keys headers)))}
        @(websocket "ws://localhost:1234" {:headers headers})
        (is (= headers (deref init-p 5000 ::timeout)))))))

(deftest test-connect-timeout
  (testing "connect timeout throws exception when server doesn't respond in time"
    (with-ws-server {:init (fn [_] (try (Thread/sleep 1000)
                                        (catch Exception _)))}
      (is (thrown? ExecutionException
                   @(websocket "ws://localhost:1234" {:connect-timeout 500}))))))

(deftest test-subprotocols
  (testing "connection with subprotocols are delivered to server"
    (let [init-p (promise)]
      (with-ws-server {:init #(deliver init-p (get-in % [:headers "sec-websocket-protocol"]))}
        @(websocket "ws://localhost:1234" {:subprotocols ["a" "b" "c"]})
        (is (= "a, b, c" (deref init-p 5000 ::timeout)))))))

(comment
  (run-tests))