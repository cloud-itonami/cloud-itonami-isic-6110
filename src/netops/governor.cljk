(ns netops.governor
  "Network Provisioning Governor -- the independent compliance layer
  that earns the Network Advisor the right to commit. The LLM has no
  notion of which jurisdiction's right-of-way/carrier-licensing
  requirements are official, whether a demand's own src/dst nodes
  actually exist in the live network, whether a demand's right-of-way
  evidence has actually been completed in full, whether a route is
  actually capacity-available, or when an act stops being a draft and
  becomes a real-world lightpath provisioning/teardown, so this MUST
  be a separate system able to *reject* a proposal and fall back to
  HOLD -- the network-operator analog of `cloud-itonami-isic-6190`'s
  TelecomAccessGovernor.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks
  a human to look, and the human may approve -- but see `netops.
  phase`: for `:stake :actuation/provision-lightpath`/`:actuation/
  teardown-lightpath` (a real-world act) NO phase ever allows auto-
  commit either. Two independent layers agree that actuation is
  always a human call.

    1. Spec-basis                  -- did the license proposal cite an
                                       OFFICIAL right-of-way/carrier-
                                       license source (`netops.facts`),
                                       or invent one?
    2. Evidence incomplete         -- for either actuation op, has the
                                       demand's right-of-way/carrier-
                                       license evidence checklist
                                       actually been satisfied?
    3. Route endpoints invalid     -- for :actuation/provision-
                                       lightpath, INDEPENDENTLY
                                       recompute whether the demand's
                                       own src/dst nodes actually exist
                                       in the live topology
                                       (`netops.registry/route-
                                       endpoints-missing?`) -- needs no
                                       proposal inspection or stored-
                                       verdict lookup, only the live
                                       topology and the demand's own
                                       recorded fields. The network-
                                       operator analog of `telecom.
                                       registry`'s `e164-invalid-
                                       format?` structural/syntactic
                                       ground-truth check.
    4. Capacity blocked            -- reported by THIS proposal itself
                                       (a :route/screen that just found
                                       :blocked), or already on file
                                       for the demand. Evaluated
                                       UNCONDITIONALLY (not scoped to
                                       one op), the same discipline
                                       `telecom.governor`'s billing-
                                       dispute check established.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below
                                       threshold, OR the op is
                                       :actuation/provision-lightpath /
                                       :actuation/teardown-lightpath
                                       (REAL acts) -> escalate.

  Two more guards, double-provisioning/double-teardown prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-provisioned-
  violations`/`already-torn-down-violations` refuse to provision/tear
  down a lightpath for the SAME demand twice, off dedicated
  `:lightpath-provisioned?`/`:lightpath-torn-down?` facts (never a
  `:status` value)."
  (:require [netops.facts :as facts]
            [netops.registry :as registry]
            [netops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Provisioning a real optical circuit and tearing one down are the two
  real-world actuation events this actor performs."
  #{:actuation/provision-lightpath :actuation/teardown-lightpath})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A :license/verify (or actuation) proposal with no spec-basis
  citation is a HARD violation -- never invent a jurisdiction's
  right-of-way/carrier-license requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:license/verify :actuation/provision-lightpath :actuation/teardown-lightpath} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は敷設許可要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For either actuation op, the jurisdiction's required right-of-way/
  carrier-license evidence must actually be satisfied -- do not trust
  the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/provision-lightpath :actuation/teardown-lightpath} op)
    (let [dm (store/demand st subject)
          verification (store/license-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction dm) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(事業者登録/占用許可/敷設記録/保全記録等)が充足していない状態での提案"}]))))

(defn- route-endpoints-invalid-violations
  "For :actuation/provision-lightpath, INDEPENDENTLY recompute whether
  the demand's own recorded src/dst nodes actually exist in the live
  topology via `netops.registry/route-endpoints-missing?` -- needs no
  proposal inspection or stored-verdict lookup, only the live topology
  and the demand's own permanent fields."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-lightpath)
    (let [dm (store/demand st subject)
          topo (store/topology st)]
      (when (registry/route-endpoints-missing? topo dm)
        [{:rule :route-endpoints-invalid
          :detail (str subject " の src(" (:src dm) ")/dst(" (:dst dm) ") がネットワーク上に存在しない")}]))))

(defn- capacity-blocked-violations
  "Route capacity blocked -- reported by THIS proposal (e.g. a :route/
  screen that itself just found :blocked), or already on file in the
  store for the demand -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY (not scoped to a specific op) so the screening op
  itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :blocked (get-in proposal [:value :verdict]))
        demand-id (when (contains? #{:route/screen :actuation/provision-lightpath} op) subject)
        hit-on-file? (and demand-id (= :blocked (:verdict (store/route-screen-of st demand-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :capacity-blocked
        :detail "経路または波長が確保できない状態でのライトパス開通提案は進められない"}])))

(defn- already-provisioned-violations
  "For :actuation/provision-lightpath, refuses to provision a
  lightpath for the SAME demand twice, off a dedicated
  `:lightpath-provisioned?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/provision-lightpath)
    (when (store/demand-already-provisioned? st subject)
      [{:rule :already-provisioned
        :detail (str subject " は既にライトパス開通済み")}])))

(defn- already-torn-down-violations
  "For :actuation/teardown-lightpath, refuses to tear down a lightpath
  for the SAME demand twice, off a dedicated `:lightpath-torn-down?`
  fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/teardown-lightpath)
    (when (store/demand-already-torn-down? st subject)
      [{:rule :already-torn-down
        :detail (str subject " は既にライトパス閉塞済み")}])))

(defn check
  "Censors a Network Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (route-endpoints-invalid-violations request st)
                           (capacity-blocked-violations request proposal st)
                           (already-provisioned-violations request st)
                           (already-torn-down-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
