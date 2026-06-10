(ns bitten.server-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.pods :as pods]
            [babashka.fs :as fs]
            [bitten.server :as server]
            [bitten.storage.core :as storage]
            [bitten.storage.sqlite :as sqlite-backend]
            [bitten.db :as db]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")

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

(defn- fresh-db []
  (let [path    (str "/tmp/bitten-server-test-" (System/nanoTime) ".db")
        backend (sqlite-backend/->SqliteBackend path)]
    (storage/migrate! backend)
    backend))

;; TCP accept loop tests ;;

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

;; fixture ;;

(def ^:dynamic *db* nil)

(defn- with-db [f]
  (let [backend (fresh-db)]
    (binding [*db* backend]
      (try (f)
           (finally (fs/delete-if-exists (:db-path backend)))))))

(use-fixtures :each with-db)

;; handle-request ;;

(deftest handle-request-ping
  (is (= {:status :ok :data :pong}
         (server/handle-request *db* {:op :ping}))))

(deftest handle-request-transact-upserts-records
  (try
    (let [response (server/handle-request *db*
                                          {:op      :transact
                                           :records [{:db/entity "user/1" :user/name "Alice"}]})]
      (is (= :ok (:status response)))
      (is (integer? (:data response)))
      (let [results (db/query *db* {:entities #{"user/1"}})]
        (is (= 1 (count results)))
        (is (= "Alice" (:user/name (first results))))))
    (finally (fs/delete-if-exists (:db-path *db*)))))

(deftest handle-request-query-returns-data
  (try
    (storage/insert-facts! *db* [{:entity "user/2" :attribute ":user/name" :value "Bob"}])
    (let [response (server/handle-request *db* {:op :query :e "user/2"})]
      (is (= :ok (:status response)))
      (is (= 1 (count (:data response))))
      (is (= "Bob" (:user/name (first (:data response))))))
    (finally (fs/delete-if-exists (:db-path *db*)))))

(deftest handle-request-unknown-op-returns-error
  (try
    (let [response (server/handle-request *db* {:op :frobulate})]
      (is (= :error (:status response)))
      (is (string? (:message response))))
    (finally (fs/delete-if-exists (:db-path *db*)))))

(deftest handle-request-parse-error-returns-error
  (try
    (let [response (server/handle-request *db* {:error "bad EDN input"})]
      (is (= :error (:status response)))
      (is (string? (:message response))))
    (finally (fs/delete-if-exists (:db-path *db*)))))
