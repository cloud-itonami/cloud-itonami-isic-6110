(ns netops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean demand through
  intake -> right-of-way/license verification -> route-capacity
  screening -> lightpath-provisioning proposal (always escalates) ->
  human approval -> commit, then through lightpath-teardown proposal
  (always escalates) -> human approval -> commit, then shows four HARD
  holds (a jurisdiction with no spec-basis, a structurally invalid
  route endpoint, a capacity-blocked route screened directly via
  :route/screen [never via an actuation op against an unscreened
  demand], and a double provisioning/teardown of an already-processed
  demand) that never reach a human at all, and prints the audit ledger
  + the draft provisioning and teardown records."
  (:require [langgraph.graph :as g]
            [netops.store :as store]
            [netops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :network-operator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== demand/intake req-1 (JPN, clean; tokyo-1 -> osaka-1) ==")
    (println (exec! actor "t1" {:op :demand/intake :subject "req-1"
                                :patch {:id "req-1" :customer-name "Sakura Community Network"}} operator))

    (println "== license/verify req-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :license/verify :subject "req-1"} operator))
    (println (approve! actor "t2"))

    (println "== route/screen req-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :route/screen :subject "req-1"} operator))
    (println (approve! actor "t3"))

    (println "== actuation/provision-lightpath req-1 (always escalates) ==")
    (let [r (exec! actor "t4" {:op :actuation/provision-lightpath :subject "req-1"} operator)]
      (println r)
      (println "-- human network operator approves --")
      (println (approve! actor "t4")))

    (println "== actuation/teardown-lightpath req-1 (always escalates) ==")
    (let [r (exec! actor "t5" {:op :actuation/teardown-lightpath :subject "req-1"} operator)]
      (println r)
      (println "-- human network operator approves --")
      (println (approve! actor "t5")))

    (println "== license/verify req-2 (ATL: no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :license/verify :subject "req-2"} operator))

    (println "== license/verify req-3 (escalates -- human approves; sets up the invalid-route-endpoint case) ==")
    (println (exec! actor "t7" {:op :license/verify :subject "req-3"} operator))
    (println (approve! actor "t7"))

    (println "== actuation/provision-lightpath req-3 (\"ghost-pop\" is not a real node -> HARD hold) ==")
    (println (exec! actor "t8" {:op :actuation/provision-lightpath :subject "req-3"} operator))

    (println "== route/screen req-4 (osaka-sapporo has no free spectrum -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :route/screen :subject "req-4"} operator))

    (println "== actuation/provision-lightpath req-1 AGAIN (double-provisioning -> HARD hold) ==")
    (println (exec! actor "t10" {:op :actuation/provision-lightpath :subject "req-1"} operator))

    (println "== actuation/teardown-lightpath req-1 AGAIN (double-teardown -> HARD hold) ==")
    (println (exec! actor "t11" {:op :actuation/teardown-lightpath :subject "req-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft lightpath-provisioning records ==")
    (doseq [r (store/provisioning-history db)] (println r))

    (println "== draft lightpath-teardown records ==")
    (doseq [r (store/teardown-history db)] (println r))))
