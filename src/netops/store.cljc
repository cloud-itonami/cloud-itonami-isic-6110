(ns netops.store
  "SSoT for the network-operator actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior `cloud-
  itonami-isic-*` actor in this fleet uses.

  R0 SCOPE NOTE: unlike most siblings (e.g. cloud-itonami-isic-6190's
  `telecom.store`, which ships both `MemStore` and a `DatomicStore`
  backed by `langchain.db`), this actor ships `MemStore` ONLY for now.
  The dual-backend `:db-api` seam (Actors playbook, root CLAUDE.md)
  still applies architecturally -- the `Store` protocol below is written
  so a `DatomicStore` can be added later without touching the governor,
  advisor or StateGraph -- but writing and proving a second backend
  against the SAME contract test is deliberately deferred rather than
  shipped unverified. Extending this is additive, not a rewrite.

  The SSoT holds two kinds of state with different lifecycles:

    - `demands`   -- customer circuit requests (id, customer name, src/
                    dst node, jurisdiction), the network-operator analog
                    of `telecom.store`'s `lines`.
    - `topology`  -- the LIVE network (an `apn.model` system: nodes,
                    DWDM links, and the lightpaths actually committed
                    into it). Provisioning a demand's lightpath mutates
                    this via `apn.provision/request`; tearing one down
                    mutates it via `apn.provision/teardown`. A demand's
                    own id IS its lightpath id in the topology -- no
                    separate mapping table.

  Like every dual-actuation sibling before it, this actor has TWO
  actuation events (provisioning a lightpath, tearing one down) acting
  on the SAME entity (a demand), each with its OWN history collection,
  sequence counter and dedicated double-actuation-guard boolean
  (`:lightpath-provisioned?`/`:lightpath-torn-down?`, never a `:status`
  value) -- the discipline informed by cloud-itonami-isic-6492's
  status-lifecycle bug (ADR-2607071320).

  The ledger stays append-only: 'which demand was screened for route
  capacity, which lightpath was provisioned, which was torn down, on
  what jurisdictional basis, approved by whom' is always a query over
  an immutable log."
  (:require [netops.registry :as registry]
            [apn.provision :as provision]
            [apn.model :as apn.model]
            [apn.grid :as apn.grid]))

(defprotocol Store
  (demand [s id])
  (all-demands [s])
  (route-screen-of [s demand-id] "committed route-capacity screening verdict for a demand, or nil")
  (license-verification-of [s demand-id] "committed right-of-way/carrier-license verification, or nil")
  (topology [s] "the live apn.model network topology")
  (ledger [s])
  (provisioning-history [s] "the append-only lightpath-provisioning history (netops.registry drafts)")
  (teardown-history [s] "the append-only lightpath-teardown history (netops.registry drafts)")
  (next-provisioning-sequence [s jurisdiction])
  (next-teardown-sequence [s jurisdiction])
  (demand-already-provisioned? [s demand-id])
  (demand-already-torn-down? [s demand-id])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-demands [s demands] "replace/seed the demand directory (map id->demand)")
  (with-topology [s topo]   "replace/seed the live network topology"))

;; ----------------------------- demo data -----------------------------

(defn demo-topology
  "A small reference APN: tokyo-1 <-> osaka-1 <-> nagoya-1 form a
  triangle with a full DWDM grid on every link (so tokyo<->osaka has an
  alternate route via nagoya). osaka-1 <-> sapporo-1 has an EMPTY
  channel set on purpose -- any demand routed through it is genuinely
  capacity-blocked, the fixture `demo-data`'s req-4 exercises."
  []
  (let [full-grid (set (apn.grid/channels))]
    (-> (apn.model/system)
        (apn.model/add-node (apn.model/node "tokyo-1" {:apn/name "Tokyo POP 1"}))
        (apn.model/add-node (apn.model/node "osaka-1" {:apn/name "Osaka POP 1"}))
        (apn.model/add-node (apn.model/node "nagoya-1" {:apn/name "Nagoya POP 1"}))
        (apn.model/add-node (apn.model/node "sapporo-1" {:apn/name "Sapporo POP 1"}))
        (apn.model/add-link (apn.model/link "tokyo-osaka" "tokyo-1" "osaka-1"
                                            {:apn/distance-km 515.0 :apn/channels full-grid}))
        (apn.model/add-link (apn.model/link "tokyo-nagoya" "tokyo-1" "nagoya-1"
                                            {:apn/distance-km 260.0 :apn/channels full-grid}))
        (apn.model/add-link (apn.model/link "nagoya-osaka" "nagoya-1" "osaka-1"
                                            {:apn/distance-km 190.0 :apn/channels full-grid}))
        (apn.model/add-link (apn.model/link "osaka-sapporo" "osaka-1" "sapporo-1"
                                            {:apn/distance-km 830.0 :apn/channels #{}})))))

(defn demo-data
  "A small, self-contained demand set covering the happy path and every
  HARD-hold case (no spec-basis, structurally invalid route endpoint,
  capacity-blocked route) so the actor + tests run offline."
  []
  {"req-1" {:id "req-1" :customer-name "Sakura Community Network"
           :src "tokyo-1" :dst "osaka-1" :jurisdiction "JPN"
           :lightpath-provisioned? false :lightpath-torn-down? false :status :intake}
   "req-2" {:id "req-2" :customer-name "Atlantis Co-op"
           :src "tokyo-1" :dst "osaka-1" :jurisdiction "ATL"
           :lightpath-provisioned? false :lightpath-torn-down? false :status :intake}
   "req-3" {:id "req-3" :customer-name "鈴木通信"
           :src "tokyo-1" :dst "ghost-pop" :jurisdiction "JPN"
           :lightpath-provisioned? false :lightpath-torn-down? false :status :intake}
   "req-4" {:id "req-4" :customer-name "田中回線"
           :src "tokyo-1" :dst "sapporo-1" :jurisdiction "JPN"
           :lightpath-provisioned? false :lightpath-torn-down? false :status :intake}})

;; ----------------------------- shared commit logic -----------------------------

(defn- provision-lightpath!
  "Backend-agnostic :demand/mark-provisioned -- runs apn.provision/
  request against the current topology and drafts the lightpath-
  provisioning record, and returns {:result .. :demand-patch ..
  :topology' ..} for the caller to persist. `netops.governor` has
  already independently re-verified the route endpoints exist and
  screened capacity before this runs; if apn.rwa still can't find a
  path/wavelength (topology changed since screening), this throws
  rather than silently drafting a record for a phantom lightpath."
  [s demand-id]
  (let [dm (demand s demand-id)
        topo (topology s)
        {:apn/keys [system' event]} (provision/request topo demand-id (:src dm) (:dst dm))]
    (when (= :blocked (:apn/t event))
      (throw (ex-info "lightpath provisioning blocked at commit time"
                      {:demand-id demand-id :reason (:apn/reason event)})))
    (let [seq-n (next-provisioning-sequence s (:jurisdiction dm))
          result (registry/register-lightpath-provisioning demand-id (:jurisdiction dm) seq-n)]
      {:result result
       :demand-patch {:lightpath-provisioned? true
                      :lightpath-path (:apn/path event)
                      :lightpath-wavelength (:apn/wavelength event)
                      :provisioning-number (get result "provisioning_number")}
       :topology' system'})))

(defn- teardown-lightpath!
  "Backend-agnostic :demand/mark-torn-down -- runs apn.provision/
  teardown against the current topology and drafts the lightpath-
  teardown record."
  [s demand-id]
  (let [dm (demand s demand-id)
        topo (topology s)
        {:apn/keys [system' event]} (provision/teardown topo demand-id)]
    (when (= :not-found (:apn/t event))
      (throw (ex-info "lightpath teardown target not found" {:demand-id demand-id})))
    (let [seq-n (next-teardown-sequence s (:jurisdiction dm))
          result (registry/register-lightpath-teardown demand-id (:jurisdiction dm) seq-n)]
      {:result result
       :demand-patch {:lightpath-torn-down? true
                      :teardown-number (get result "teardown_number")}
       :topology' system'})))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (demand [_ id] (get-in @a [:demands id]))
  (all-demands [_] (sort-by :id (vals (:demands @a))))
  (route-screen-of [_ demand-id] (get-in @a [:route-screens demand-id]))
  (license-verification-of [_ demand-id] (get-in @a [:licenses demand-id]))
  (topology [_] (:topology @a))
  (ledger [_] (:ledger @a))
  (provisioning-history [_] (:provisionings @a))
  (teardown-history [_] (:teardowns @a))
  (next-provisioning-sequence [_ jurisdiction] (get-in @a [:provisioning-sequences jurisdiction] 0))
  (next-teardown-sequence [_ jurisdiction] (get-in @a [:teardown-sequences jurisdiction] 0))
  (demand-already-provisioned? [_ demand-id] (boolean (get-in @a [:demands demand-id :lightpath-provisioned?])))
  (demand-already-torn-down? [_ demand-id] (boolean (get-in @a [:demands demand-id :lightpath-torn-down?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :demand/upsert
      (swap! a update-in [:demands (:id value)] merge value)

      :license/set
      (swap! a assoc-in [:licenses (first path)] payload)

      :route-screen/set
      (swap! a assoc-in [:route-screens (first path)] payload)

      :demand/mark-provisioned
      (let [demand-id (first path)
            {:keys [result demand-patch topology']} (provision-lightpath! s demand-id)
            jurisdiction (:jurisdiction (demand s demand-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:provisioning-sequences jurisdiction] (fnil inc 0))
                       (update-in [:demands demand-id] merge demand-patch)
                       (update :provisionings registry/append result)
                       (assoc :topology topology'))))
        result)

      :demand/mark-torn-down
      (let [demand-id (first path)
            {:keys [result demand-patch topology']} (teardown-lightpath! s demand-id)
            jurisdiction (:jurisdiction (demand s demand-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:teardown-sequences jurisdiction] (fnil inc 0))
                       (update-in [:demands demand-id] merge demand-patch)
                       (update :teardowns registry/append result)
                       (assoc :topology topology'))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-demands [s demands] (when (seq demands) (swap! a assoc :demands demands)) s)
  (with-topology [s topo] (swap! a assoc :topology topo) s))

(defn seed-db
  "A MemStore seeded with the demo demand set and reference topology.
  The deterministic default."
  []
  (->MemStore (atom {:demands (demo-data) :topology (demo-topology)
                     :licenses {} :route-screens {} :ledger []
                     :provisioning-sequences {} :provisionings []
                     :teardown-sequences {} :teardowns []})))
