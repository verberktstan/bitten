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

(defn- insert-raw! [db entity attribute value valid-time tx-time tx-id retracted]
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
  (insert-raw! db "user/alice" ":user/name"  "Alice"         "2024-01-01" "2024-01-01" 1 0)
  (insert-raw! db "user/alice" ":user/email" "a@example.com" "2024-01-01" "2024-01-01" 1 0)
  (insert-raw! db "user/bob"   ":user/name"  "Bob"           "2024-01-01" "2024-01-01" 1 0)
  (insert-raw! db "user/carol" ":user/name"  "Carol"         "2024-01-01" "2024-01-01" 1 0)
  (insert-raw! db "user/alice" ":user/name"  "Alicia"        "2024-06-01" "2024-06-01" 2 0)
  (insert-raw! db "user/bob"   ":user/name"  "Bob Smith"     "2024-01-01" "2024-09-01" 3 0)
  (insert-raw! db "user/carol" ":user/name"  "Carol"         "2024-01-01" "2024-11-01" 4 1))

(defn- name-of [results entity]
  (->> results
       (filter #(and (= entity (:db/entity %))
                     (= ":user/name" (:db/attribute %))))
       first
       :db/value))

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
                                  :valid-time "2024-03-01"
                                  :tx-time    "2024-12-31"})]
    (is (= 2 (count res)))
    (is (some #(= % {:db/entity "user/alice" :db/attribute ":user/name"  :db/value "Alice"})         res))
    (is (some #(= % {:db/entity "user/alice" :db/attribute ":user/email" :db/value "a@example.com"}) res))))

(deftest name-changed-in-valid-time
  ;; querying after alice's new valid-time returns the updated name
  (let [res (db/query-as-of *db* {:entity     "user/alice"
                                  :valid-time "2024-07-01"
                                  :tx-time    "2024-12-31"})]
    (is (= "Alicia" (name-of res "user/alice")))))

(deftest query-before-name-change-valid-time
  ;; querying before alice's new valid-time still returns the old name
  (let [res (db/query-as-of *db* {:entity     "user/alice"
                                  :valid-time "2024-05-01"
                                  :tx-time    "2024-12-31"})]
    (is (= "Alice" (name-of res "user/alice")))))

(deftest retroactive-correction-before-known
  ;; querying bob as of tx-time BEFORE the correction was recorded → original value
  (let [res (db/query-as-of *db* {:entity     "user/bob"
                                  :valid-time "2024-03-01"
                                  :tx-time    "2024-06-01"})]
    (is (= "Bob" (name-of res "user/bob")))))

(deftest retroactive-correction-after-known
  ;; querying bob as of tx-time AFTER the correction was recorded → corrected value
  (let [res (db/query-as-of *db* {:entity     "user/bob"
                                  :valid-time "2024-03-01"
                                  :tx-time    "2024-12-31"})]
    (is (= "Bob Smith" (name-of res "user/bob")))))

(deftest retracted-fact-returns-empty
  ;; carol's name was retracted; entity should return no results
  (let [res (db/query-as-of *db* {:entity     "user/carol"
                                  :valid-time "2024-12-31"
                                  :tx-time    "2024-12-31"})]
    (is (empty? res))))

(deftest no-entity-filter-returns-all-entities
  ;; omitting :entity returns facts for every entity
  (let [res      (db/query-as-of *db* {:valid-time "2024-12-31" :tx-time "2024-12-31"})
        entities (->> res (map :db/entity) set)]
    (is (contains? entities "user/alice"))
    (is (contains? entities "user/bob"))
    ;; carol is fully retracted, should not appear
    (is (not (contains? entities "user/carol")))))

(deftest query-before-any-facts-returns-empty
  (let [res (db/query-as-of *db* {:entity     "user/alice"
                                  :valid-time "2023-01-01"
                                  :tx-time    "2024-12-31"})]
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
                                    :valid-time "9999-12-31"
                                    :tx-time    "9999-12-31"})]
    (is (integer? tx-id))
    (is (= 2 (count res)))))

(deftest insert-facts-defaults-valid-time-to-now
  ;; no :valid-time supplied; fact is still queryable at far-future valid-time
  (let [_   (db/insert-facts! *db* [{:entity    "user/frank"
                                     :attribute ":user/name"
                                     :value     "Frank"}])
        res (db/query-as-of *db* {:entity     "user/frank"
                                  :valid-time "9999-12-31"
                                  :tx-time    "9999-12-31"})]
    (is (= 1 (count res)))
    (is (= "Frank" (:db/value (first res))))))
