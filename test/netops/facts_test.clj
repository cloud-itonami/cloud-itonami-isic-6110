(ns netops.facts-test
  (:require [clojure.test :refer [deftest is]]
            [netops.facts :as facts]))

(deftest spec-basis-known-jurisdiction
  (is (some? (facts/spec-basis "JPN")))
  (is (= "Japan" (:name (facts/spec-basis "JPN")))))

(deftest spec-basis-unknown-jurisdiction-is-nil
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-is-honest
  (let [c (facts/coverage ["JPN" "ATL" "USA"])]
    (is (= 3 (:requested c)))
    (is (= 2 (:covered c)))
    (is (= ["ATL"] (:missing-jurisdictions c)))
    (is (= ["JPN" "USA"] (:covered-jurisdictions c)))))

(deftest required-evidence-satisfied-needs-full-checklist
  (let [full (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" full))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest full))))
    (is (not (facts/required-evidence-satisfied? "ATL" [])))))
