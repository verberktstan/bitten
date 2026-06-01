(ns db-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.pods :as pods]
            [babashka.fs :as fs]
            [db :as db]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

;; helpers ;;

(defn- fresh-db []
  (let [path (str "/tmp/bitten-test-" (System/nanoTime) ".db")]
    (-> path db/migrate! (assoc :db-path path))))

(defn- insert-raw! [db {:keys [entity attribute value valid-time tx-time tx-id retracted] :as fact}]
  {:pre [(db/assert-fact fact :full)]}
  (sqlite/execute! db
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

(defn- seed-db! [db]
  (insert-raw! db {:entity "user/alice" :attribute ":user/name"  :value "Alice"
                   :valid-time "2024-01-01" :tx-time "2024-01-01"
                   :tx-id 1 :retracted false})
  (insert-raw! db {:entity "user/alice" :attribute ":user/email" :value "a@example.com"
                   :valid-time "2024-01-01" :tx-time "2024-01-01" :tx-id 1
                   :retracted false})
  (insert-raw! db {:entity "user/bob"   :attribute ":user/name"  :value "Bob"
                   :valid-time "2024-01-01" :tx-time "2024-01-01"
                   :tx-id 1 :retracted false})
  (insert-raw! db {:entity "user/carol" :attribute ":user/name"  :value "Carol"
                   :valid-time "2024-01-01" :tx-time "2024-01-01"
                   :tx-id 1 :retracted false})
  (insert-raw! db {:entity "user/alice" :attribute ":user/name"  :value "Alicia"
                   :valid-time "2024-06-01" :tx-time "2024-06-01"
                   :tx-id 2 :retracted false})
  (insert-raw! db {:entity "user/bob"   :attribute ":user/name"  :value "Bob Smith"
                   :valid-time "2024-01-01" :tx-time "2024-09-01"
                   :tx-id 3 :retracted false})
  (insert-raw! db {:entity "user/carol" :attribute ":user/name"  :value "Carol"
                   :valid-time "2024-01-01" :tx-time "2024-11-01"
                   :tx-id 4 :retracted true}))

(defn- name-of [results entity]
  (->> results
       (filter #(and (= entity (:db/entity %))
                     (= ":user/name" (:db/attribute %))))
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

(defn with-seeded-db [f]
  (let [{:keys [db-path]} (fresh-db)]
    (seed-db! db-path)
    (binding [*db* db-path]
      (try (f)
           (finally (fs/delete-if-exists db-path))))))

(use-fixtures :each with-seeded-db)

;; query-as-of ;;

(deftest basic-entity-lookup
  ;; alice at a time when only tx-1 facts exist
  (let [res (db/query-as-of *db* {:entity     "user/alice"
                                  :valid-time before-alice-renamed
                                  :tx-time    after-all-events})]
    (is (= 2 (count res)))
    (is (some #(= % {:db/entity "user/alice" :db/attribute ":user/name"  :db/value "Alice"})         res))
    (is (some #(= % {:db/entity "user/alice" :db/attribute ":user/email" :db/value "a@example.com"}) res))))

(deftest name-changed-in-valid-time
  ;; querying after alice's new valid-time returns the updated name
  (let [res (db/query-as-of *db* {:entity     "user/alice"
                                  :valid-time after-alice-renamed
                                  :tx-time    after-all-events})]
    (is (= "Alicia" (name-of res "user/alice")))))

(deftest query-before-name-change-valid-time
  ;; querying before alice's new valid-time still returns the old name
  (let [res (db/query-as-of *db* {:entity     "user/alice"
                                  :valid-time before-alice-renamed
                                  :tx-time    after-all-events})]
    (is (= "Alice" (name-of res "user/alice")))))

(deftest retroactive-correction-before-known
  ;; querying bob as of tx-time BEFORE the correction was recorded → original value
  (let [res (db/query-as-of *db* {:entity     "user/bob"
                                  :valid-time before-alice-renamed
                                  :tx-time    before-bob-corrected})]
    (is (= "Bob" (name-of res "user/bob")))))

(deftest retroactive-correction-after-known
  ;; querying bob as of tx-time AFTER the correction was recorded → corrected value
  (let [res (db/query-as-of *db* {:entity     "user/bob"
                                  :valid-time before-alice-renamed
                                  :tx-time    after-all-events})]
    (is (= "Bob Smith" (name-of res "user/bob")))))

(deftest retracted-fact-returns-empty
  ;; carol's name was retracted; entity should return no results
  (let [res (db/query-as-of *db* {:entity     "user/carol"
                                  :valid-time after-all-events
                                  :tx-time    after-all-events})]
    (is (empty? res))))

(deftest no-entity-filter-returns-all-entities
  ;; omitting :entity returns facts for every entity
  (let [res      (db/query-as-of *db* {:valid-time after-all-events :tx-time after-all-events})
        entities (->> res (map :db/entity) set)]
    (is (contains? entities "user/alice"))
    (is (contains? entities "user/bob"))
    ;; carol is fully retracted, should not appear
    (is (not (contains? entities "user/carol")))))

(deftest query-before-any-facts-returns-empty
  (let [res (db/query-as-of *db* {:entity     "user/alice"
                                  :valid-time before-any-facts
                                  :tx-time    after-all-events})]
    (is (empty? res))))

;;; ── insert-facts! ───────────────────────────────────────────────────────────

(deftest insert-facts-increments-tx-id
  ;; db is pre-seeded with tx_ids 1–4; new inserts must continue the sequence
  (let [tx-id-1 (db/insert-facts! *db* [{:entity    "user/dave"
                                         :attribute ":user/name"
                                         :value     "Dave"}])
        tx-id-2 (db/insert-facts! *db* [{:entity    "user/dave"
                                         :attribute ":user/email"
                                         :value     "dave@example.com"}])]
    (is (integer? tx-id-1))
    (is (= (inc tx-id-1) tx-id-2))))

(deftest insert-facts-groups-batch-under-same-tx-id
  (let [tx-id (db/insert-facts! *db* [{:entity "user/eve" :attribute ":user/name"  :value "Eve"}
                                      {:entity "user/eve" :attribute ":user/email" :value "eve@example.com"}])
        res   (db/query-as-of *db* {:entity     "user/eve"
                                    :valid-time far-future
                                    :tx-time    far-future})]
    (is (integer? tx-id))
    (is (= 2 (count res)))))

(deftest insert-facts-defaults-valid-time-to-now
  ;; no :valid-time supplied; fact is still queryable at far-future valid-time
  (let [_   (db/insert-facts! *db* [{:entity    "user/frank"
                                     :attribute ":user/name"
                                     :value     "Frank"}])
        res (db/query-as-of *db* {:entity     "user/frank"
                                  :valid-time far-future
                                  :tx-time    far-future})]
    (is (= 1 (count res)))
    (is (= "Frank" (:db/value (first res))))))
