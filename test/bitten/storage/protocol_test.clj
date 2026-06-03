(ns bitten.storage.protocol-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [bitten.storage.protocol :as protocol]))

;; parse-request ;;

(deftest parse-request-returns-edn-map
  (is (= {:op :transact :facts [{:e "user/1" :a :user/name :v "Alice"}]}
         (protocol/parse-request
           "{:op :transact :facts [{:e \"user/1\" :a :user/name :v \"Alice\"}]}"))))

(deftest parse-request-malformed-edn-returns-error
  (is (contains? (protocol/parse-request "{") :error)))

(deftest parse-request-empty-string-returns-error
  (is (contains? (protocol/parse-request "") :error)))

;; serialize-response ;;

(deftest serialize-response-produces-edn-string
  (is (= {:status :ok :data 1}
         (edn/read-string (protocol/serialize-response {:status :ok :data 1})))))

(deftest serialize-response-round-trips
  ;; pr-str map key order is not guaranteed; parse back to verify content
  (let [response {:status :ok :data [{:db/entity "user/1" :user/name "Alice"}]}]
    (is (= response
           (edn/read-string (protocol/serialize-response response))))))

(deftest serialize-response-error-round-trips
  (let [response {:status :error :message "unknown op: :foo"}]
    (is (= response
           (edn/read-string (protocol/serialize-response response))))))
