(ns netops.governor-contract-test
  "The governor contract as executable tests -- the network-operator
  analog of cloud-itonami-isic-6190's telecom.governor-contract-test.
  The single invariant under test:

    Network Advisor never provisions or tears down a lightpath the
    Network Provisioning Governor would reject, :actuation/provision-
    lightpath / :actuation/teardown-lightpath NEVER auto-commit at any
    phase, :demand/intake (no direct capital risk) MAY auto-commit
    when clean, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [netops.store :as store]
            [netops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :network-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through license verification -> approve, leaving a
  right-of-way/carrier-license verification on file."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :license/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :demand/intake :subject "req-1"
                   :patch {:id "req-1" :customer-name "Sakura Community Network"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Community Network" (:customer-name (store/demand db "req-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest license-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :license/verify :subject "req-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/license-verification-of db "req-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a license/verify proposal for a jurisdiction with no official spec-basis (req-2 is ATL) -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :license/verify :subject "req-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/license-verification-of db "req-2")) "no verification written"))))

(deftest provision-without-verification-is-held
  (testing "actuation/provision-lightpath before any license verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/provision-lightpath :subject "req-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest route-endpoints-invalid-is-held
  (testing "a demand whose own recorded dst node (\"ghost-pop\") doesn't exist in the topology -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "req-3")
          res (exec-op actor "t5" {:op :actuation/provision-lightpath :subject "req-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:route-endpoints-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/provisioning-history db))))))

(deftest capacity-blocked-is-held-and-unoverridable
  (testing "req-4 routes through osaka-sapporo, which has zero free spectrum -> HOLD, never reaches a human -- exercised via :route/screen DIRECTLY, not via an actuation op against an unscreened demand (mirrors telecom.governor-contract-test's billing-dispute-is-held-and-unoverridable)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :route/screen :subject "req-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:capacity-blocked} (-> (store/ledger db) first :basis)))
      (is (nil? (store/route-screen-of db "req-4")) "no clearance written"))))

(deftest provision-lightpath-always-escalates-then-human-decides
  (testing "a clean, fully license-verified demand still ALWAYS interrupts for human approval -- actuation/provision-lightpath is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "req-1")
          r1 (exec-op actor "t7" {:op :actuation/provision-lightpath :subject "req-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, provisioning record drafted, topology mutated"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:lightpath-provisioned? (store/demand db "req-1"))))
          (is (= 1 (count (store/provisioning-history db)))))))))

(deftest teardown-lightpath-always-escalates-then-human-decides
  (testing "a provisioned demand's teardown still ALWAYS interrupts for human approval -- actuation/teardown-lightpath is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "req-1")
          _ (exec-op actor "t8a" {:op :actuation/provision-lightpath :subject "req-1"} operator)
          _ (approve! actor "t8a")
          r1 (exec-op actor "t8" {:op :actuation/teardown-lightpath :subject "req-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, teardown record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:lightpath-torn-down? (store/demand db "req-1"))))
          (is (= 1 (count (store/teardown-history db)))))))))

(deftest provision-lightpath-double-provisioning-is-held
  (testing "provisioning the same demand's lightpath twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "req-1")
          _ (exec-op actor "t9a" {:op :actuation/provision-lightpath :subject "req-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :actuation/provision-lightpath :subject "req-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-provisioned} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/provisioning-history db))) "still only the one earlier provisioning"))))

(deftest teardown-lightpath-double-teardown-is-held
  (testing "tearing down the same demand's lightpath twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "req-1")
          _ (exec-op actor "t10a" {:op :actuation/provision-lightpath :subject "req-1"} operator)
          _ (approve! actor "t10a")
          _ (exec-op actor "t10b" {:op :actuation/teardown-lightpath :subject "req-1"} operator)
          _ (approve! actor "t10b")
          res (exec-op actor "t10" {:op :actuation/teardown-lightpath :subject "req-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-torn-down} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/teardown-history db))) "still only the one earlier teardown"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :demand/intake :subject "req-1"
                          :patch {:id "req-1" :customer-name "Sakura Community Network"}} operator)
      (exec-op actor "b" {:op :license/verify :subject "req-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
