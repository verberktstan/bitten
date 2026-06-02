(ns server-test
  (:require [clojure.test :refer [deftest is]]
            [server :as server]))

;; Helpers ;;

(defn- connect! [port]
  (let [socket (java.net.Socket. "localhost" port)]
    {:socket socket
     :writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream socket)))
     :reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream socket)))}))

(defn- send-line! [{:keys [writer]} line]
  (.write writer line)
  (.newLine writer)
  (.flush writer))

(defn- recv-line! [{:keys [reader]}]
  (.readLine reader))

(defn- close-conn! [{:keys [socket]}]
  (.close socket))

(defn- echo-handler [line] (str "echo:" line))

;; Tests ;;

(deftest server-responds-to-a-single-line
  ;; Port 0 asks the OS for a free ephemeral port.
  (let [srv  (server/start-server! 0 echo-handler)
        port (.getLocalPort srv)]
    (try
      (let [conn (connect! port)]
        (try
          (send-line! conn "hello")
          (is (= "echo:hello" (recv-line! conn)))
          (finally (close-conn! conn))))
      (finally (.close srv)))))

(deftest server-handles-multiple-lines-per-connection
  ;; All lines written before reading; tests sequential response ordering.
  (let [srv  (server/start-server! 0 echo-handler)
        port (.getLocalPort srv)]
    (try
      (let [conn (connect! port)]
        (try
          (send-line! conn "first")
          (send-line! conn "second")
          (is (= "echo:first"  (recv-line! conn)))
          (is (= "echo:second" (recv-line! conn)))
          (finally (close-conn! conn))))
      (finally (.close srv)))))

(deftest server-survives-client-disconnect
  ;; A client that connects and immediately closes must not crash the accept loop.
  ;; A subsequent connection must succeed normally.
  (let [srv  (server/start-server! 0 echo-handler)
        port (.getLocalPort srv)]
    (try
      (.close (java.net.Socket. "localhost" port))
      (Thread/sleep 50)
      (let [conn (connect! port)]
        (try
          (send-line! conn "after-disconnect")
          (is (= "echo:after-disconnect" (recv-line! conn)))
          (finally (close-conn! conn))))
      (finally (.close srv)))))
