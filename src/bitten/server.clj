(ns bitten.server
  (:require [bitten.db :as db]))

(defn- buffered-reader [input-stream]
  (-> input-stream java.io.InputStreamReader. java.io.BufferedReader.))

(defn- buffered-writer [output-stream]
  (-> output-stream  java.io.OutputStreamWriter. java.io.BufferedWriter.))

(defn- socket-stream [socket direction]
  (case direction
    :in  (-> socket .getInputStream buffered-reader)
    :out (-> socket .getOutputStream buffered-writer)))

(defn- handle-connection! [socket handler]
  (with-open [socket socket]
    (let [reader (socket-stream socket :in)
          writer (socket-stream socket :out)]
      (loop []
        (when-let [line (.readLine reader)]
          (.write writer (handler line))
          (.newLine writer)
          (.flush writer)
          (recur))))))

(defn- parse-query-opts [{:keys [e as-of-valid]}]
  (cond-> {}
    e           (assoc :entities   #{e})
    as-of-valid (assoc :valid-time as-of-valid)))

(defn handle-request [backend {:keys [op error] :as request}]
  (cond
    error            {:status :error :message error}
    (= op :ping)     {:status :ok :data :pong}
    (= op :transact) {:status :ok :data (db/upsert! backend (:records request))}
    (= op :query)    {:status :ok :data (db/query backend (parse-query-opts request))}
    :else            {:status :error :message (str "unknown op: " op)}))

(defn- accept-loop
  "Accepts connections from server until it is closed.
   SocketException on .accept is how .close signals shutdown — swallowed intentionally."
  [server handler]
  (try
    (loop []
      (future (handle-connection! (.accept server) handler))
      (recur))
    (catch java.net.SocketException _ nil)))

(defn start-server!
  "Starts a TCP server on port (0 = OS-assigned). Spawns one thread per accepted
   connection; each thread calls handler with each newline-delimited line and writes
   the returned string as a response line. Returns the ServerSocket; call .close on
   it to stop accepting new connections."
  [port handler]
  (let [server (java.net.ServerSocket. port)]
    (future (accept-loop server handler))
    server))
