(ns netops.registry
  "Pure-function lightpath-provisioning + lightpath-teardown record
  construction -- an append-only network-operator book-of-record draft
  -- plus the independent structural route-endpoint check the governor
  uses as ground truth.

  Like cloud-itonami-isic-6190's telecom.registry, there is no single
  international check-digit standard for a provisioning/teardown
  reference number -- every operator/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number, the same honest, non-
  fabricating discipline `netops.facts` uses.

  `route-endpoints-missing?` plays the role telecom.registry's
  `e164-invalid-format?` plays there: a ground-truth recompute
  independent of any advisor self-report -- but here the ground truth
  lives in the live network topology (an apn.model system), not in a
  single record's own field, so it needs one store lookup (the
  topology) rather than none. `netops.governor` calls it directly
  against the live topology before any :actuation/provision-lightpath
  is allowed to commit.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real optical switch/ROADM/EMS. It builds the RECORD an
  operator would keep, not the act of provisioning or tearing down the
  lightpath itself (that is `netops.operation`'s :actuation/provision-
  lightpath / :actuation/teardown-lightpath, always human-gated -- see
  README `Actuation`)."
  (:require [clojure.string :as str]
            [apn.model :as apn-model]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  operator's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn route-endpoints-missing?
  "Does demand's :src or :dst NOT exist as a node in topology (an
  apn.model system)? A pure ground-truth recompute against the live
  topology's own node directory -- no upstream comparison, no trusting
  the advisor's self-reported confidence."
  [topology {:keys [src dst]}]
  (or (nil? (apn-model/node-by-id topology src))
      (nil? (apn-model/node-by-id topology dst))))

(defn register-lightpath-provisioning
  "Validate + construct the LIGHTPATH-PROVISIONING registration DRAFT --
  the operator's own act of activating a real optical circuit for a
  demand. Pure function -- does not touch any real ROADM/EMS; it
  builds the RECORD an operator would keep. `netops.governor`
  independently re-verifies the demand's route endpoints and blocks a
  double-provisioning for the same demand before this is ever allowed
  to commit."
  [demand-id jurisdiction sequence]
  (when-not (and demand-id (not= demand-id ""))
    (throw (ex-info "lightpath-provisioning: demand_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "lightpath-provisioning: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "lightpath-provisioning: sequence must be >= 0" {})))
  (let [provisioning-number (str (str/upper-case jurisdiction) "-PRV-" (zero-pad sequence 6))
        record {"record_id" provisioning-number
                "kind" "lightpath-provisioning-draft"
                "demand_id" demand-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "provisioning_number" provisioning-number
     "certificate" (unsigned-certificate "LightpathProvisioning" provisioning-number provisioning-number)}))

(defn register-lightpath-teardown
  "Validate + construct the LIGHTPATH-TEARDOWN registration DRAFT -- the
  operator's own act of tearing down a real optical circuit for a
  demand. Pure function -- does not touch any real ROADM/EMS; it
  builds the RECORD an operator would keep. `netops.governor`
  independently re-verifies and blocks a double-teardown for the same
  demand before this is ever allowed to commit. Like `telecom.
  registry/register-billing-suppression`, this actuation is a NEGATIVE
  act (releasing/withdrawing a circuit), not a positive one (issuing
  one)."
  [demand-id jurisdiction sequence]
  (when-not (and demand-id (not= demand-id ""))
    (throw (ex-info "lightpath-teardown: demand_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "lightpath-teardown: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "lightpath-teardown: sequence must be >= 0" {})))
  (let [teardown-number (str (str/upper-case jurisdiction) "-TRN-" (zero-pad sequence 6))
        record {"record_id" teardown-number
                "kind" "lightpath-teardown-draft"
                "demand_id" demand-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "teardown_number" teardown-number
     "certificate" (unsigned-certificate "LightpathTeardown" teardown-number teardown-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
