(ns netops.store-test
  "R0 SCOPE NOTE: this exercises the single MemStore backend directly --
  see netops.store's ns docstring for why there is no cross-backend
  contract test (yet) the way cloud-itonami-isic-6190's telecom.store-
  contract-test proves MemStore ≡ DatomicStore."
  (:require [clojure.test :refer [deftest is]]
            [apn.model :as m]
            [netops.store :as store]))

(deftest seed-db-has-demo-fixtures
  (let [db (store/seed-db)]
    (is (= 4 (count (store/all-demands db))))
    (is (some? (store/demand db "req-1")))
    (is (= 4 (count (m/nodes (store/topology db)))))))

(deftest demand-upsert-merges
  (let [db (store/seed-db)]
    (store/commit-record! db {:effect :demand/upsert :value {:id "req-1" :customer-name "New Name"}})
    (is (= "New Name" (:customer-name (store/demand db "req-1"))))))

(deftest provision-then-teardown-round-trips-topology
  (let [db (store/seed-db)]
    (store/commit-record! db {:effect :demand/mark-provisioned :path ["req-1"]})
    (is (true? (:lightpath-provisioned? (store/demand db "req-1"))))
    (is (= 1 (count (store/provisioning-history db))))
    (is (= 1 (count (m/lightpaths (store/topology db)))))
    (store/commit-record! db {:effect :demand/mark-torn-down :path ["req-1"]})
    (is (true? (:lightpath-torn-down? (store/demand db "req-1"))))
    (is (= 1 (count (store/teardown-history db))))
    (is (= :torn-down (:apn/state (m/lightpath-by-id (store/topology db) "req-1"))))))

(deftest sequence-counters-increment-per-jurisdiction
  (let [db (store/seed-db)]
    (is (= 0 (store/next-provisioning-sequence db "JPN")))
    (store/commit-record! db {:effect :demand/mark-provisioned :path ["req-1"]})
    (is (= 1 (store/next-provisioning-sequence db "JPN")))))

(deftest ledger-append-only
  (let [db (store/seed-db)]
    (store/append-ledger! db {:t :test-fact-1})
    (store/append-ledger! db {:t :test-fact-2})
    (is (= [{:t :test-fact-1} {:t :test-fact-2}] (store/ledger db)))))

(deftest already-provisioned-and-torn-down-flags
  (let [db (store/seed-db)]
    (is (not (store/demand-already-provisioned? db "req-1")))
    (store/commit-record! db {:effect :demand/mark-provisioned :path ["req-1"]})
    (is (store/demand-already-provisioned? db "req-1"))
    (is (not (store/demand-already-torn-down? db "req-1")))
    (store/commit-record! db {:effect :demand/mark-torn-down :path ["req-1"]})
    (is (store/demand-already-torn-down? db "req-1"))))
