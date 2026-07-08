(ns netops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [apn.model :as m]
            [netops.registry :as registry]))

(deftest register-lightpath-provisioning-shape
  (let [{:strs [record provisioning_number certificate]}
        (registry/register-lightpath-provisioning "req-1" "JPN" 0)]
    (is (= "JPN-PRV-000000" provisioning_number))
    (is (= "req-1" (get record "demand_id")))
    (is (true? (get record "immutable")))
    (is (= "draft-unsigned" (get certificate "status")))))

(deftest register-lightpath-provisioning-requires-fields
  (is (thrown? Exception (registry/register-lightpath-provisioning nil "JPN" 0)))
  (is (thrown? Exception (registry/register-lightpath-provisioning "req-1" nil 0)))
  (is (thrown? Exception (registry/register-lightpath-provisioning "req-1" "JPN" -1))))

(deftest register-lightpath-teardown-shape
  (let [{:strs [record teardown_number]}
        (registry/register-lightpath-teardown "req-1" "JPN" 3)]
    (is (= "JPN-TRN-000003" teardown_number))
    (is (= "lightpath-teardown-draft" (get record "kind")))))

(deftest register-lightpath-teardown-requires-fields
  (is (thrown? Exception (registry/register-lightpath-teardown nil "JPN" 0)))
  (is (thrown? Exception (registry/register-lightpath-teardown "req-1" "" 0)))
  (is (thrown? Exception (registry/register-lightpath-teardown "req-1" "JPN" -1))))

(deftest route-endpoints-missing-detects-absent-nodes
  (let [topo (-> (m/system) (m/add-node (m/node "a" {})))]
    (is (registry/route-endpoints-missing? topo {:src "a" :dst "ghost"}))
    (is (registry/route-endpoints-missing? topo {:src "ghost" :dst "a"}))
    (is (not (registry/route-endpoints-missing?
              (m/add-node topo (m/node "b" {}))
              {:src "a" :dst "b"})))))

(deftest append-conj-record
  (let [result (registry/register-lightpath-provisioning "req-1" "JPN" 0)]
    (is (= [(get result "record")] (registry/append [] result)))))
