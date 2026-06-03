(ns bitten.sqlite
  (:require [babashka.pods :as pods]
            [clojure.string :as str]
            [bitten.storage]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite-pod])

(def data-definition
  "CREATE TABLE IF NOT EXISTS facts (
     id         INTEGER PRIMARY KEY AUTOINCREMENT,
     entity     TEXT    NOT NULL,
     attribute  TEXT    NOT NULL,
     value      TEXT    NOT NULL,
     valid_time TEXT    NOT NULL,
     tx_time    TEXT    NOT NULL,
     tx_id      INTEGER NOT NULL,
     retracted  BOOLEAN NOT NULL DEFAULT false
   )")

(defn- now-iso []
  (-> (java.time.Instant/now) str))

(defn- next-tx-id! [conn]
  (-> (sqlite-pod/query conn ["SELECT COALESCE(MAX(tx_id), 0) + 1 AS next_id FROM facts"])
      first
      :next_id))

(defn- parse-attr [s]
  (let [v (clojure.edn/read-string s)]
    (if (keyword? v) v s)))

(defn- select-ranked-facts [where-clause]
  (str
   "WITH ranked AS (
      SELECT entity, attribute, value, retracted,
      ROW_NUMBER() OVER (
        PARTITION BY entity, attribute
        ORDER BY valid_time DESC, tx_time DESC, id DESC
      ) AS rn
      FROM facts"
   where-clause
   ")
    SELECT entity, attribute, value
    FROM ranked
    WHERE rn = 1 AND retracted = false"))

(defn- row->datom [row]
  {:db/entity    (:entity row)
   :db/attribute (-> row :attribute parse-attr)
   :db/value     (:value row)})

(defrecord SqliteBackend [db-path])

(extend-type SqliteBackend
  bitten.storage/IStorage

  (migrate! [{:keys [db-path]}]
    (sqlite-pod/execute! db-path [data-definition]))

  (insert-facts! [{:keys [db-path]} facts]
    (let [tx-time (now-iso)
          conn    (sqlite-pod/get-connection db-path)]
      (try
        (sqlite-pod/execute! conn ["BEGIN"])
        (let [tx-id (next-tx-id! conn)]
          (doseq [{:keys [entity attribute value valid-time retracted]
                   :or   {valid-time tx-time retracted false}} facts]
            (sqlite-pod/execute! conn
                                 ["INSERT INTO facts (entity, attribute, value, valid_time, tx_time, tx_id, retracted)
                                   VALUES (?, ?, ?, ?, ?, ?, ?)"
                                  (str entity) (str attribute) (str value) valid-time tx-time tx-id retracted]))
          (sqlite-pod/execute! conn ["COMMIT"])
          tx-id)
        (catch Exception e
          (try (sqlite-pod/execute! conn ["ROLLBACK"]) (catch Exception _ nil))
          (throw e))
        (finally
          (sqlite-pod/close-connection conn)))))

  (query-as-of [{:keys [db-path]} {:keys [entities valid-time tx-time]}]
    (let [n-entities   (when (seq entities) (count entities))
          qmarks       (when n-entities
                         (->> "?" (repeat n-entities) (str/join ", ")))
          filters      (cond-> []
                         tx-time    (conj ["tx_time <= ?" [tx-time]])
                         valid-time (conj ["valid_time <= ?" [valid-time]])
                         n-entities (conj [(str "entity IN (" qmarks ")") (vec entities)]))
          where-clause (when (seq filters)
                         (str " WHERE " (str/join " AND " (map first filters))))
          params       (->> filters (mapcat second) vec)
          sql          (select-ranked-facts where-clause)]
      (->> (sqlite-pod/query db-path (into [sql] params))
           (map row->datom)))))
