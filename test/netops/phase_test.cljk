(ns netops.phase-test
  (:require [clojure.test :refer [deftest is]]
            [netops.phase :as phase]))

(deftest gate-hold-always-stays-hold
  (is (= {:disposition :hold :reason nil}
         (phase/gate 3 {:op :demand/intake} :hold))))

(deftest gate-write-op-disabled-in-phase-holds
  (is (= :hold (:disposition (phase/gate 0 {:op :demand/intake} :commit))))
  (is (= :phase-disabled (:reason (phase/gate 0 {:op :demand/intake} :commit)))))

(deftest gate-actuation-never-auto-at-any-phase
  (doseq [phase [0 1 2 3]]
    (let [{:keys [disposition]} (phase/gate phase {:op :actuation/provision-lightpath} :commit)]
      (is (not= :commit disposition)
          (str "phase " phase " must never auto-commit actuation/provision-lightpath")))
    (let [{:keys [disposition]} (phase/gate phase {:op :actuation/teardown-lightpath} :commit)]
      (is (not= :commit disposition)
          (str "phase " phase " must never auto-commit actuation/teardown-lightpath")))))

(deftest gate-route-screen-never-auto-at-any-phase
  (doseq [phase [0 1 2 3]]
    (let [{:keys [disposition]} (phase/gate phase {:op :route/screen} :commit)]
      (is (not= :commit disposition)))))

(deftest gate-demand-intake-auto-commits-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :demand/intake} :commit)))))

(deftest verdict->disposition-mapping
  (is (= :hold (phase/verdict->disposition {:hard? true})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
