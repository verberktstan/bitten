(ns db
  (:require [storage]))

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

(defn diff->facts
  "Given a diff map and context, returns {:changed [...] :retracted [...]}.

   Expects keys: :from-db, :from-record (from clojure.data/diff), :entity,
   :missing-keys (:ignore or :retract)."
  [{:keys [from-db from-record entity missing-keys]}]
  {:changed   (map (fn [[k v]] {:entity entity :attribute k :value v})
                   from-record)
   :retracted (when (= missing-keys :retract)
                ;; from-db holds both changed and gone keys; subtract the changed
                ;; ones (present in from-record) to isolate keys that vanished entirely.
                (->> (apply dissoc from-db (keys from-record))
                     (map (fn [[k v]]
                            {:entity    entity
                             :attribute k
                             :value     v
                             :retracted true}))))})

(defn query
  "Returns storage/query-as-of results as a sequence of flat maps, each with :db/entity."
  [backend opts]
  (->> (storage/query-as-of backend opts)
       (reduce (fn [acc {:db/keys [entity attribute value]}]
                 (update acc entity (fnil assoc {:db/entity entity}) attribute value))
               {})
       vals))

(defn upsert!
  "Applies data-records to the backend, writing only changed key/value pairs as facts.
   Each record must contain :db/entity; remaining keys are attribute/value pairs.
   Returns the assigned tx-id, or nil when no facts changed.

   opts:
     :missing-keys — :ignore (default) leaves absent attributes untouched;
                     :retract retracts attributes present in the db but absent
                     from the incoming record."
  ([backend records] (upsert! backend records {}))
  ([backend records {:keys [missing-keys] :or {missing-keys :ignore}}]
   (let [entities    (into #{} (map :db/entity records))
         current-idx (->> (query backend {:entities entities}) index-by-entity)
         facts       (for [record  records
                           :let    [entity              (:db/entity record)
                                    incoming            (dissoc record :db/entity)
                                    existing            (get current-idx entity {})
                                    diff                (-> (zipmap [:from-db :from-record]
                                                                    (clojure.data/diff existing incoming))
                                                            (assoc :entity entity :missing-keys missing-keys))
                                    {:keys [changed retracted]} (diff->facts diff)]]
                      (concat changed retracted))
         all-facts   (into [] cat facts)]
     (when (seq all-facts)
       (storage/insert-facts! backend all-facts)))))
