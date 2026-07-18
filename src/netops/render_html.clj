(ns netops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`netops.operation` -> `netops.governor` -> `netops.store`) through a
  scenario adapted from this repo's own `netops.sim` demo driver
  (`clojure -M:dev:run`, confirmed by actually running it before this
  file was written -- its ids (`req-1`..`req-4`) and topology
  (`tokyo-1`/`osaka-1`/`nagoya-1`/`sapporo-1`, the `osaka-sapporo` link
  with an empty channel set) match `netops.store/demo-data` and
  `netops.store/demo-topology` exactly, and every disposition it
  produces (auto-commit / escalate+approve / HARD hold, and the exact
  `:rule` on each hold) matches `netops.governor`'s own documented six
  checks precisely, so it was safe to reuse rather than author from
  scratch), trimmed to a representative subset (the phase-3 auto-commit
  + full escalate/approve lifecycle for req-1's demand, plus three
  distinct HARD-hold reasons that never reach a human) and rendered
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verified
  by diffing two consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [netops.store :as store]
            [netops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :network-operator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real demand ids from
  `netops.store/demo-data` and the real reference topology from
  `netops.store/demo-topology`:

  req-1 (JPN, clean; tokyo-1 -> osaka-1, customer 'Sakura Community
  Network') walks the full clean lifecycle: a `:demand/intake`
  directory-normalization patch is a phase-3, no-capital-risk
  auto-commit (governor clean, `:demand/intake` is the ONLY op in
  phase 3's `:auto` set); `:license/verify` (JPN has a real spec-basis
  in `netops.facts`) and `:route/screen` (clean -- `apn.rwa/assign`
  actually finds a live route/wavelength) each ALWAYS escalate (neither
  op is ever auto-eligible, at any phase) and are approved by a human
  network operator; `:actuation/provision-lightpath` and
  `:actuation/teardown-lightpath` -- the two REAL-WORLD actuation
  events this actor performs (activating / releasing a real optical
  circuit) -- ALSO ALWAYS escalate (the governor's own `high-stakes`
  gate AND the phase table agree, independently, that actuation is
  never auto, at any phase) and are each approved, producing one draft
  lightpath-provisioning record (`JPN-PRV-000000`) and one draft
  lightpath-teardown record (`JPN-TRN-000000`).

  Then three DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - req-2 (jurisdiction ATL, not in `netops.facts/catalog`):
      `:license/verify` HARD-holds on `:no-spec-basis` -- the advisor
      may not invent a jurisdiction's right-of-way/carrier-licensing
      requirements.
    - req-3 (JPN, dst `ghost-pop` -- not a real node in the live
      topology): assessed first (`:license/verify` clean
      escalate+approve, so evidence is on file and the HARD hold below
      is isolated to the route-endpoint check alone), then
      `:actuation/provision-lightpath` HARD-holds on
      `:route-endpoints-invalid` -- the governor independently
      recomputes whether req-3's own recorded src/dst nodes actually
      exist in the live `apn.model` topology.
    - req-4 (JPN, tokyo-1 -> sapporo-1, which can only be reached via
      the `osaka-sapporo` link -- seeded with an EMPTY DWDM channel
      set): `:route/screen` HARD-holds on `:capacity-blocked` -- no
      free wavelength on the only route, discovered by actually running
      `apn.rwa/assign` against the live topology, not a self-report.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; req-1: clean directory-normalization patch -- phase-3 auto-commit,
    ;; no capital risk yet.
    (exec! actor "r1-intake" {:op :demand/intake :subject "req-1"
                               :patch {:id "req-1" :customer-name "Sakura Community Network"}})

    ;; req-1: right-of-way/carrier-license verification (JPN has a real
    ;; spec-basis) -- ALWAYS escalates, approved by a human.
    (exec! actor "r1-verify" {:op :license/verify :subject "req-1"})
    (approve! actor "r1-verify")

    ;; req-1: route-capacity screening, clean -- ALWAYS escalates,
    ;; approved by a human.
    (exec! actor "r1-screen" {:op :route/screen :subject "req-1"})
    (approve! actor "r1-screen")

    ;; req-1: REAL lightpath provisioning (actuation/provision-lightpath,
    ;; activates a real optical circuit) -- ALWAYS escalates regardless
    ;; of phase or confidence, approved by a human network operator.
    (exec! actor "r1-provision" {:op :actuation/provision-lightpath :subject "req-1"})
    (approve! actor "r1-provision")

    ;; req-1: REAL lightpath teardown (actuation/teardown-lightpath,
    ;; releases a real optical circuit) -- ALWAYS escalates, approved by
    ;; a human.
    (exec! actor "r1-teardown" {:op :actuation/teardown-lightpath :subject "req-1"})
    (approve! actor "r1-teardown")

    ;; req-2 (ATL): no official spec-basis in netops.facts -> HARD hold
    ;; on :no-spec-basis, never reaches a human.
    (exec! actor "r2-verify" {:op :license/verify :subject "req-2"})

    ;; req-3: verify JPN first (clean escalate+approve) so evidence is on
    ;; file and the route-endpoint hold below is isolated.
    (exec! actor "r3-verify" {:op :license/verify :subject "req-3"})
    (approve! actor "r3-verify")

    ;; req-3: dst "ghost-pop" does not exist in the live topology -> HARD
    ;; hold on :route-endpoints-invalid, never reaches a human.
    (exec! actor "r3-provision" {:op :actuation/provision-lightpath :subject "req-3"})

    ;; req-4: only route (via osaka-sapporo) has an empty DWDM channel
    ;; set -> HARD hold on :capacity-blocked, never reaches a human.
    (exec! actor "r4-screen" {:op :route/screen :subject "req-4"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- demand-row [ledger {:keys [id customer-name src dst jurisdiction
                                   lightpath-provisioned? lightpath-torn-down?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc customer-name) (esc src) (esc dst) (esc jurisdiction)
          (if lightpath-provisioned?
            (if lightpath-torn-down? "provisioned &middot; torn down" "provisioned")
            "not provisioned")
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- record-row [prefix {:strs [record_id demand_id jurisdiction kind immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc prefix) (esc record_id) (esc demand_id) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" (esc kind))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`netops.governor`/`netops.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately hand-
  ;; described rather than derived from a live run.
  ["        <tr><td><code>:demand/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet -- the ONLY auto-eligible op in this domain</span></td></tr>"
   "        <tr><td><code>:license/verify</code></td><td><span class=\"warn\">ALWAYS human approval &middot; spec-basis independently checked against <code>netops.facts</code>, never fabricated</span></td></tr>"
   "        <tr><td><code>:route/screen</code></td><td><span class=\"warn\">ALWAYS human approval when clean &middot; a capacity-blocked route (no free DWDM wavelength) is a HARD, un-overridable hold instead</span></td></tr>"
   "        <tr><td><code>:actuation/provision-lightpath</code></td><td><span class=\"warn\">ALWAYS human approval &middot; activates a real optical circuit &middot; route endpoints independently recomputed against the live topology + double-provisioning guard enforced, never auto at any phase</span></td></tr>"
   "        <tr><td><code>:actuation/teardown-lightpath</code></td><td><span class=\"warn\">ALWAYS human approval &middot; releases a real optical circuit &middot; double-teardown guard enforced, never auto at any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        demands (store/all-demands db)
        demand-rows (str/join "\n" (map (partial demand-row ledger) demands))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        provisioning-rows (str/join "\n" (map (partial record-row "provisioning") (store/provisioning-history db)))
        teardown-rows (str/join "\n" (map (partial record-row "teardown") (store/teardown-history db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6110 &middot; wired telecommunications activities</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Wired Telecommunications Network Operations (ISIC 6110) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · lightpath provisioning/teardown always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Circuit demands</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>netops.store</code> via <code>netops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Demand</th><th>Customer</th><th>Src node</th><th>Dst node</th><th>Jurisdiction</th><th>Lightpath</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     demand-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft lightpath-provisioning / lightpath-teardown records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — activating/releasing the real optical circuit is the licensed network operator's own act, outside this actor's authority (see README <code>Actuation</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Demand</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     provisioning-rows (when (seq provisioning-rows) "\n")
     teardown-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Network Provisioning Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Right-of-way/carrier-license spec-basis, evidence completeness, live route endpoints and route-capacity are independently recomputed, never trusted from the advisor's proposal; activating or releasing a real optical circuit is always a human network operator's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/provisioning-history db)) "provisioning drafts,"
             (count (store/teardown-history db)) "teardown drafts )")))
