(ns db
  (:require [babashka.pods :as pods]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def ^:private data-definition
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

(defn migrate! [db]
  (sqlite/execute! db [data-definition]))

(defn- now-iso []
  ;; UTC wall clock, matches ISO-8601 lexicographic ordering
  (-> (java.time.Instant/now) str))

(defn- next-tx-id! [db]
  (-> (sqlite/query db ["SELECT COALESCE(MAX(tx_id), 0) + 1 AS next_id FROM facts"])
      first
      :next_id))

(def ->eav (juxt :entity :attribute :value))

(defn- assert-fact
  [fact]
  (every? some? (->eav fact)))

(defn insert-facts!
  "Appends facts to the log in a single transaction. Returns the assigned tx-id.
   Each fact map requires :entity, :attribute, :value.
   :valid-time is optional and defaults to the current wall-clock time."
  [db facts]
  {:pre [(every? assert-fact facts)]}
  (let [tx-time (now-iso)
        conn    (sqlite/get-connection db)]
    (try
      (sqlite/execute! conn ["BEGIN"])
      (let [tx-id (next-tx-id! conn)]
        (doseq [{:keys [entity attribute value valid-time]
                 :or   {valid-time tx-time}} facts]
          (sqlite/execute! conn
                           ["INSERT INTO facts (entity, attribute, value, valid_time, tx_time, tx_id)
              VALUES (?, ?, ?, ?, ?, ?)"
                            (str entity) (str attribute) (str value) valid-time tx-time tx-id]))
        (sqlite/execute! conn ["COMMIT"])
        tx-id)
      (catch Exception e
        (try (sqlite/execute! conn ["ROLLBACK"]) (catch Exception _ nil))
        (throw e))
      (finally
       (sqlite/close-connection conn)))))

(defn query-as-of
  "Bi-temporal point query. Returns a seq of {:db/entity :db/attribute :db/value}.

   opts:
     :entity     — optional; omit to return all entities
     :valid-time — the valid-time point to query (ISO-8601 string)
     :tx-time    — the transaction-time point to query (ISO-8601 string)

   For each (entity, attribute) pair considers only facts recorded at or before
   :tx-time and valid at or before :valid-time, then picks the most recent one
   (by valid_time DESC, tx_time DESC, id DESC). Suppresses retracted facts."
  [db {:keys [entity valid-time tx-time]}]
  (let [entity-clause (when entity " AND entity = ?")
        sql (str "WITH ranked AS (
                    SELECT entity, attribute, value, retracted,
                      ROW_NUMBER() OVER (
                        PARTITION BY entity, attribute
                        ORDER BY valid_time DESC, tx_time DESC, id DESC
                      ) AS rn
                    FROM facts
                    WHERE tx_time <= ? AND valid_time <= ?"
                 entity-clause
                 ")
                  SELECT entity, attribute, value
                  FROM ranked
                  WHERE rn = 1 AND retracted = false")
        params (cond-> [tx-time valid-time]
                 entity (conj entity))]
    (->> (sqlite/query db (into [sql] params))
         (map (fn [row]
                {:db/entity    (:entity row)
                 :db/attribute (:attribute row)
                 :db/value     (:value row)})))))
