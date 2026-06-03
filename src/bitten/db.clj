(ns bitten.db
  (:require [bitten.storage.core :as storage]
            [clojure.data :as data]))

(def ->eav      (juxt :entity :attribute :value))
(def ->full-eav (juxt :entity :attribute :value :valid-time :tx-time :tx-id :retracted))

(defn assert-fact
  ([fact]
   (assert-fact fact nil))
  ([fact full?]
   (let [convert-fact (if full? ->full-eav ->eav)]
     (every? some? (convert-fact fact)))))

(defn- index-by-entity [records]
  (into {} (map (fn [r] [(:db/entity r) (dissoc r :db/entity)]) records)))

(defn- changed-facts [entity from-record]
  (map (fn [[k v]] {:entity entity :attribute k :value v}) from-record))

(defn- retracted-fact [entity]
  (fn retracted-fact* [[k v]]
    {:entity entity :attribute k :value v :retracted true}))

(defn- retracted-facts
  "Returns retraction facts for keys that vanished from the db record entirely.
   from-db includes both changed and gone keys; subtracting from-record isolates the gone ones."
  [entity from-db from-record]
  (->> (apply dissoc from-db (keys from-record))
       (map (retracted-fact entity))))

(defn diff->facts [{:keys [from-db from-record entity missing-keys]}]
  {:changed   (changed-facts entity from-record)
   :retracted (when (= missing-keys :retract)
                (retracted-facts entity from-db from-record))})

(defn query
  "Returns storage/query-as-of results as a sequence of flat maps, each with :db/entity."
  [backend opts]
  (->> (storage/query-as-of backend opts)
       (reduce (fn query* [acc {:db/keys [entity attribute value]}]
                 (update acc entity (fnil assoc {:db/entity entity}) attribute value))
               {})
       vals))

(defn- record->diff [record existing missing-keys]
  (let [entity   (:db/entity record)
        incoming (dissoc record :db/entity)]
    (-> (zipmap [:from-db :from-record] (data/diff existing incoming))
        (assoc :entity entity :missing-keys missing-keys))))

(def changed-and-retracted (juxt :changed :retracted))

(defn upsert!
  "Writes only changed facts from records to backend. Returns tx-id or nil.
   (upsert! db [{:db/entity \"user/1\" :name \"Alice\"}])
   :missing-keys — :ignore (default) leaves absent attributes untouched;
   :retract removes them."
  ([backend records] (upsert! backend records nil))
  ([backend records {:keys [missing-keys] :or {missing-keys :ignore}}]
   (let [entities    (into #{} (map :db/entity records))
         current-idx (->> {:entities entities} (query backend) index-by-entity)
         facts       (for [{:db/keys [entity] :as record} records
                           :let [existing   (get current-idx entity {})
                                 diff-facts (-> record
                                                (record->diff existing missing-keys)
                                                diff->facts)]]
                       (->> diff-facts changed-and-retracted (apply concat)))]
     (when-let [all-facts (->> facts (into [] cat) seq)]
       (storage/insert-facts! backend all-facts)))))

(defn- record->retraction-facts [record]
  (map (retracted-fact (:db/entity record)) (dissoc record :db/entity)))

(defn retract!
  "Retracts all live facts for each entity in entity-ids.
   Returns the tx-id, or nil when no live facts exist for the given entities."
  [backend entity-ids]
  (let [all-facts (->> {:entities (set entity-ids)}
                       (query backend)
                       (into [] (mapcat record->retraction-facts)))]
    (when (seq all-facts)
      (storage/insert-facts! backend all-facts))))
