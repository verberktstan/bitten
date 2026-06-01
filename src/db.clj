(ns db
  (:require [babashka.pods :as pods]
            [clojure.string :as str]))

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
(def ->full-eav (juxt :entity :attribute :value :valid-time :tx-time :tx-id :retracted))

(defn assert-fact
  ([fact]
   (assert-fact fact nil))
  ([fact full?]
   (let [convert-fact (if full? ->full-eav ->eav)]
     (every? some? (convert-fact fact)))))

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

(defn query-as-of
  "Bi-temporal point query. Returns a seq of {:db/entity :db/attribute :db/value}.

   opts (all optional):
     :entities   — set of entity strings to restrict results; omit for all entities
     :valid-time — only consider facts valid at or before this ISO-8601 string;
                   omit to ignore the valid-time axis entirely
     :tx-time    — only consider facts recorded at or before this ISO-8601 string;
                   omit to ignore the transaction-time axis entirely

   For each (entity, attribute) pair picks the most recent surviving fact
   (by valid_time DESC, tx_time DESC, id DESC). Suppresses retracted facts."
  [db {:keys [entities valid-time tx-time]}]
  (let [qmarks       (->> "?" (repeat (count entities)) (str/join ", "))
        filters      (cond-> []
                       tx-time          (conj ["tx_time <= ?" [tx-time]])
                       valid-time       (conj ["valid_time <= ?" [valid-time]])
                       (seq entities)   (conj [(str "entity IN (" qmarks ")")
                                               (vec entities)]))
        where-clause (when (seq filters)
                       (str " WHERE " (str/join " AND " (map first filters))))
        params       (->> filters (mapcat second) vec)
        sql          (select-ranked-facts where-clause)]
    (->> (sqlite/query db (into [sql] params))
         (map (fn [row]
                {:db/entity    (:entity row)
                 :db/attribute (-> row :attribute parse-attr)
                 :db/value     (:value row)})))))

(defn query
  "Returns query-as-of results as a nested map of {entity {attribute value}}."
  [db opts]
  (reduce (fn [acc {:db/keys [entity attribute value]}]
            (update acc entity assoc attribute value))
          {}
          (query-as-of db opts)))
