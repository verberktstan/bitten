(ns bitten.db-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.pods :as pods]
            [babashka.fs :as fs]
            [bitten.db :as db]
            [bitten.storage.core :as storage]
            [bitten.storage.sqlite :as sqlite-backend]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sql-pod])

;; Helpers ;;

(defn- fresh-db []
  (let [path    (str "/tmp/bitten-test-" (System/nanoTime) ".db")
        backend (sqlite-backend/->SqliteBackend path)]
    (storage/migrate! backend)
    backend))

(defn- insert-raw! [backend {:keys [entity attribute value valid-time tx-time tx-id retracted] :as fact}]
  {:pre [(db/assert-fact fact :full)]}
  (sql-pod/execute! (:db-path backend)
    ["INSERT INTO facts (entity, attribute, value, valid_time, tx_time, tx_id, retracted)
      VALUES (?, ?, ?, ?, ?, ?, ?)"
     entity attribute value valid-time tx-time tx-id retracted]))

;;; Test dataset
;;;
;;; tx 1 (2024-01-01) — initial facts
;;;   user/alice  :user/name   "Alice"          valid 2024-01-01
;;;   user/alice  :user/email  "a@example.com"  valid 2024-01-01
;;;   user/bob    :user/name   "Bob"            valid 2024-01-01
;;;   user/carol  :user/name   "Carol"          valid 2024-01-01
;;;
;;; tx 2 (2024-06-01) — alice changes her name (later valid-time)
;;;   user/alice  :user/name   "Alicia"         valid 2024-06-01
;;;
;;; tx 3 (2024-09-01) — retroactive correction for bob
;;;   (same valid-time as original, later tx-time → wins via tx_time DESC)
;;;   user/bob    :user/name   "Bob Smith"      valid 2024-01-01
;;;
;;; tx 4 (2024-11-01) — carol's name retracted entirely
;;;   user/carol  :user/name   "Carol"          valid 2024-01-01  retracted=1

(def ^:private seed-facts
  [{:entity "user/alice" :attribute ":user/name"  :value "Alice"
    :valid-time "2024-01-01" :tx-time "2024-01-01" :tx-id 1 :retracted false}
   {:entity "user/alice" :attribute ":user/email" :value "a@example.com"
    :valid-time "2024-01-01" :tx-time "2024-01-01" :tx-id 1 :retracted false}
   {:entity "user/bob"   :attribute ":user/name"  :value "Bob"
    :valid-time "2024-01-01" :tx-time "2024-01-01" :tx-id 1 :retracted false}
   {:entity "user/carol" :attribute ":user/name"  :value "Carol"
    :valid-time "2024-01-01" :tx-time "2024-01-01" :tx-id 1 :retracted false}
   {:entity "user/alice" :attribute ":user/name"  :value "Alicia"
    :valid-time "2024-06-01" :tx-time "2024-06-01" :tx-id 2 :retracted false}
   {:entity "user/bob"   :attribute ":user/name"  :value "Bob Smith"
    :valid-time "2024-01-01" :tx-time "2024-09-01" :tx-id 3 :retracted false}
   {:entity "user/carol" :attribute ":user/name"  :value "Carol"
    :valid-time "2024-01-01" :tx-time "2024-11-01" :tx-id 4 :retracted true}])

(defn- seed-db! [backend]
  (run! (partial insert-raw! backend) seed-facts))

(defn- name-of [results entity]
  (->> results
       (filter #(and (= entity (:db/entity %))
                     (= :user/name (:db/attribute %))))
       first
       :db/value))

;; Time constants for navigating the test dataset.
;; All are relative to the four transactions documented above.
(def before-any-facts     "2023-01-01") ; predates every recorded fact
(def before-alice-renamed "2024-05-01") ; valid-time: before alice's rename (effective 2024-06-01)
(def after-alice-renamed  "2024-07-01") ; valid-time: after alice's rename took effect
(def before-bob-corrected "2024-06-01") ; tx-time: before bob's correction was recorded (tx 3, 2024-09-01)
(def after-all-events     "2024-12-31") ; valid-time or tx-time: after all test transactions
(def far-future           "9999-12-31") ; beyond all test data; used in insert tests

;; fixture ;;

(def ^:dynamic *db* nil)

(defn- with-seeded-db [f]
  (let [backend (fresh-db)]
    (seed-db! backend)
    (binding [*db* backend]
      (try (f)
           (finally (fs/delete-if-exists (:db-path backend)))))))

(use-fixtures :each with-seeded-db)

;; query-as-of ;;

(deftest basic-entity-lookup
  ;; alice at a time when only tx-1 facts exist
  (let [res (storage/query-as-of *db* {:entities   #{"user/alice"}
                                       :valid-time before-alice-renamed})]
    (is (= 2 (count res)))
    (is (some #(= % {:db/entity "user/alice" :db/attribute :user/name  :db/value "Alice"})         res))
    (is (some #(= % {:db/entity "user/alice" :db/attribute :user/email :db/value "a@example.com"}) res))))

(deftest name-changed-in-valid-time
  ;; querying after alice's new valid-time returns the updated name
  (let [res (storage/query-as-of *db* {:entities   #{"user/alice"}
                                       :valid-time after-alice-renamed})]
    (is (= "Alicia" (name-of res "user/alice")))))

(deftest query-before-name-change-valid-time
  ;; querying before alice's new valid-time still returns the old name
  (let [res (storage/query-as-of *db* {:entities   #{"user/alice"}
                                       :valid-time before-alice-renamed})]
    (is (= "Alice" (name-of res "user/alice")))))

(deftest retroactive-correction-before-known
  ;; querying bob as of tx-time BEFORE the correction was recorded → original value
  (let [res (storage/query-as-of *db* {:entities   #{"user/bob"}
                                       :valid-time before-alice-renamed
                                       :tx-time    before-bob-corrected})]
    (is (= "Bob" (name-of res "user/bob")))))

(deftest retroactive-correction-after-known
  ;; querying bob as of tx-time AFTER the correction was recorded → corrected value
  (let [res (storage/query-as-of *db* {:entities   #{"user/bob"}
                                       :valid-time before-alice-renamed})]
    (is (= "Bob Smith" (name-of res "user/bob")))))

(deftest retracted-fact-returns-empty
  ;; carol's name was retracted; entity should return no results
  (let [res (storage/query-as-of *db* {:entities #{"user/carol"}})]
    (is (empty? res))))

(deftest no-entity-filter-returns-all-entities
  ;; omitting :entity returns facts for every entity
  (let [res      (storage/query-as-of *db* {})
        entities (->> res (map :db/entity) set)]
    (is (contains? entities "user/alice"))
    (is (contains? entities "user/bob"))
    ;; carol is fully retracted, should not appear
    (is (not (contains? entities "user/carol")))))

(deftest query-before-any-facts-returns-empty
  (let [res (storage/query-as-of *db* {:entities   #{"user/alice"}
                                       :valid-time before-any-facts})]
    (is (empty? res))))

;; query ;;

(defn- entity-record
  "Returns the single flat record for an entity from the db, or nil."
  [backend entity]
  (let [props {:entities #{entity}}]
    (-> backend (db/query props) first)))

(deftest query-returns-flat-maps-with-entity-key
  (let [result (set (db/query *db* {}))]
    (is (contains? result {:db/entity "user/alice" :user/name "Alicia" :user/email "a@example.com"}))
    (is (contains? result {:db/entity "user/bob"   :user/name "Bob Smith"}))
    ;; carol is retracted — must not appear
    (is (not (contains? (set (map :db/entity result)) "user/carol")))))

(deftest query-scoped-to-single-entity
  (let [result (set (db/query *db* {:entities #{"user/alice"}}))]
    (is (= #{{:db/entity "user/alice" :user/name "Alicia" :user/email "a@example.com"}}
           result))))

(deftest query-scoped-to-multiple-entities
  (let [result (set (db/query *db* {:entities #{"user/alice" "user/bob"}}))]
    (is (= #{{:db/entity "user/alice" :user/name "Alicia" :user/email "a@example.com"}
             {:db/entity "user/bob"   :user/name "Bob Smith"}}
           result))))

;; insert-facts! ;;

(deftest insert-facts-increments-tx-id
  ;; db is pre-seeded with tx_ids 1–4; new inserts must continue the sequence
  (let [tx-id-1 (storage/insert-facts! *db* [{:entity    "user/dave"
                                               :attribute ":user/name"
                                               :value     "Dave"}])
        tx-id-2 (storage/insert-facts! *db* [{:entity    "user/dave"
                                               :attribute ":user/email"
                                               :value     "dave@example.com"}])]
    (is (integer? tx-id-1))
    (is (= (inc tx-id-1) tx-id-2))))

(deftest insert-facts-groups-batch-under-same-tx-id
  (let [tx-id (storage/insert-facts! *db* [{:entity "user/eve" :attribute ":user/name"  :value "Eve"}
                                            {:entity "user/eve" :attribute ":user/email" :value "eve@example.com"}])
        res   (storage/query-as-of *db* {:entities #{"user/eve"}})]
    (is (integer? tx-id))
    (is (= 2 (count res)))))

(deftest insert-facts-defaults-valid-time-to-now
  ;; no :valid-time supplied; fact is still queryable at far-future valid-time
  (let [_   (storage/insert-facts! *db* [{:entity    "user/frank"
                                           :attribute ":user/name"
                                           :value     "Frank"}])
        res (storage/query-as-of *db* {:entities #{"user/frank"}})]
    (is (= 1 (count res)))
    (is (= "Frank" (:db/value (first res))))))

(deftest insert-facts-retracted-fact-is-suppressed
  ;; inserting a retracted fact should hide it from query results
  (storage/insert-facts! *db* [{:entity    "user/alice"
                                 :attribute ":user/email"
                                 :value     "a@example.com"
                                 :retracted true}])
  (is (nil? (:user/email (entity-record *db* "user/alice")))))

;; diff->facts ;;

(deftest diff->facts-happy-path-changed-and-retracted
  ;; Core invariant: a changed key appears in :changed with the NEW value;
  ;; a gone key appears in :retracted — but the changed key does NOT,
  ;; even though clojure.data/diff puts it in both partitions.
  (let [{:keys [changed retracted]}
        (db/diff->facts {:from-db     {:user/name "Alice" :user/email "a@example.com"}
                         :from-record {:user/name "Alicia"}
                         :entity      "user/alice"
                         :missing-keys :retract})]
    (is (= [{:entity "user/alice" :attribute :user/name :value "Alicia"}]
           (vec changed)))
    (is (= [{:entity "user/alice" :attribute :user/email :value "a@example.com" :retracted true}]
           (vec retracted)))))

(deftest diff->facts-no-op-returns-empty
  ;; When both diff partitions are nil nothing is produced.
  (let [{:keys [changed retracted]}
        (db/diff->facts {:from-db nil :from-record nil
                         :entity "user/alice" :missing-keys :ignore})]
    (is (empty? changed))
    (is (nil? retracted))))

(deftest diff->facts-ignore-does-not-retract-gone-keys
  ;; :ignore mode suppresses retractions regardless of what is in from-db.
  (let [{:keys [changed retracted]}
        (db/diff->facts {:from-db     {:user/email "a@example.com"}
                         :from-record nil
                         :entity      "user/alice"
                         :missing-keys :ignore})]
    (is (empty? changed))
    (is (nil? retracted))))

;; upsert! ;;

(deftest upsert-no-op-returns-nil
  ;; upserting unchanged values writes nothing and returns nil
  (let [result (db/upsert! *db* [{:db/entity  "user/alice"
                                   :user/name  "Alicia"
                                   :user/email "a@example.com"}])]
    (is (nil? result))))

(deftest upsert-changed-value-writes-only-diff
  ;; only the changed attribute is written; untouched attributes survive
  (db/upsert! *db* [{:db/entity "user/alice" :user/name "Alicia V2"}])
  (let [record (entity-record *db* "user/alice")]
    (is (= "Alicia V2"    (:user/name record)))
    (is (= "a@example.com" (:user/email record)))))

(deftest upsert-new-entity-writes-all-attributes
  (db/upsert! *db* [{:db/entity "user/dave" :user/name "Dave"}])
  (is (= {:db/entity "user/dave" :user/name "Dave"}
         (entity-record *db* "user/dave"))))

(deftest upsert-missing-keys-ignore-leaves-existing
  (db/upsert! *db* [{:db/entity "user/alice" :user/name "Alicia V2"}]
              {:missing-keys :ignore})
  (let [record (entity-record *db* "user/alice")]
    (is (= "Alicia V2"    (:user/name record)))
    (is (= "a@example.com" (:user/email record)))))

(deftest upsert-missing-keys-retract-removes-absent
  (db/upsert! *db* [{:db/entity "user/alice" :user/name "Alicia V2"}]
              {:missing-keys :retract})
  (let [record (entity-record *db* "user/alice")]
    (is (= "Alicia V2" (:user/name record)))
    (is (nil? (:user/email record)))))

(deftest upsert-multiple-records-in-one-transaction
  (let [tx-id (db/upsert! *db* [{:db/entity "user/alice" :user/name "Alicia V2"}
                                 {:db/entity "user/bob"   :user/name "Bobby"}])]
    (is (integer? tx-id))
    (is (= "Alicia V2" (:user/name (entity-record *db* "user/alice"))))
    (is (= "Bobby"     (:user/name (entity-record *db* "user/bob"))))))

;; retract! ;;

(deftest retract-entity-removes-all-live-attributes
  ;; alice has :user/name and :user/email; both are gone after retraction
  (db/retract! *db* ["user/alice"])
  (is (nil? (entity-record *db* "user/alice"))))

(deftest retract-nonexistent-entity-is-a-no-op
  ;; entity never written → query returns nothing → nil, no error
  (is (nil? (db/retract! *db* ["user/nobody"]))))

(deftest retract-already-retracted-entity-is-a-no-op
  ;; carol is fully retracted in seed data; retract! must not crash or write new rows
  (is (nil? (db/retract! *db* ["user/carol"]))))

(deftest retract-multiple-entities
  ;; both alice and bob are removed in one call
  (db/retract! *db* ["user/alice" "user/bob"])
  (is (nil? (entity-record *db* "user/alice")))
  (is (nil? (entity-record *db* "user/bob"))))

(deftest retract-preserves-unretracted-entities
  ;; retracting alice must leave bob's facts intact
  (db/retract! *db* ["user/alice"])
  (is (some? (entity-record *db* "user/bob"))))
