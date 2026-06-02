(ns server)

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

(defn start-server!
  "Starts a TCP server on port (0 = OS-assigned). Spawns one thread per accepted
   connection; each thread calls handler with each newline-delimited line and writes
   the returned string as a response line. Returns the ServerSocket; call .close on
   it to stop accepting new connections."
  [port handler]
  (let [server (java.net.ServerSocket. port)]
    (future
      (try
        (loop []
          (let [client (.accept server)]
            (future (handle-connection! client handler))
            (recur)))
        (catch java.net.SocketException _
          nil)))   ; .close on server causes .accept to throw and exit the loop
    server))
