(ns netops.phase
  "Phase 0->3 staged rollout -- the network-operator analog of `cloud-
  itonami-isic-6190`'s `telecom.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- demand intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-verify  -- adds right-of-way/carrier-license
                                 verification + route-capacity
                                 screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 :demand/intake (no capital risk yet)
                                 may auto-commit. :actuation/provision-
                                 lightpath / :actuation/teardown-
                                 lightpath NEVER auto-commit, at any
                                 phase.

  :actuation/provision-lightpath / :actuation/teardown-lightpath are
  deliberately ABSENT from every phase's :auto set, including phase 3
  -- a permanent structural fact, not a rollout milestone still to
  come. Activating or releasing a real optical circuit is always a
  human network operator's call. `netops.governor`'s high-stakes gate
  enforces the same invariant independently -- two layers, not one,
  agree on this. :route/screen is likewise never auto-eligible, at any
  phase.")

(def read-ops  #{})
(def write-ops #{:demand/intake :license/verify :route/screen
                 :actuation/provision-lightpath :actuation/teardown-lightpath})

;; NOTE the invariant: :actuation/provision-lightpath / :actuation/
;; teardown-lightpath are members of write-ops (governor-gated like
;; any write) but are NEVER members of any phase's :auto set below. Do
;; not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                          :auto #{}}
   1 {:label "assisted-intake"  :writes #{:demand/intake}                                             :auto #{}}
   2 {:label "assisted-verify"  :writes #{:demand/intake :license/verify :route/screen}                :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:demand/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - :actuation/provision-lightpath / :actuation/teardown-lightpath are
    never auto-eligible at any phase, so they always escalate once the
    governor clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Network Provisioning Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
