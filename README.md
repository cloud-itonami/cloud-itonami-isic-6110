# cloud-itonami-isic-6110

Open Business Blueprint for **ISIC Rev.5 6110**: wired telecommunications
activities (operating and maintaining facilities to provide wired
telecommunications services -- wired transport backbone, wholesale
wavelength/dark-fibre leasing, carrier-of-carriers circuit provisioning).

This repository publishes a **wired-network operator actor** -- demand
intake, right-of-way/carrier-license verification, route-capacity
screening, lightpath provisioning and lightpath teardown -- as an OSS
business that any qualified community network operator can fork, deploy,
run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem checkpoints) and
[`kotoba-lang/apn`](https://github.com/kotoba-lang/apn) (the pure
All-Photonics-Network topology + Routing-and-Wavelength-Assignment
library this actor wraps) -- the same actor pattern as every prior actor
in this fleet, most directly
[`cloud-itonami-isic-6190`](https://github.com/cloud-itonami/cloud-itonami-isic-6190)
(other telecommunications: line intake / billing-dispute screening /
number provisioning), whose shape this actor mirrors op-for-op. Here it
is **Network Advisor ⊣ Network Provisioning Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a demand-
> intake summary and normalizing records -- but it has **no notion of**
> which jurisdiction's right-of-way/carrier-licensing requirements are
> official, whether a demand's own recorded src/dst nodes actually exist
> on the live network, whether a route is actually capacity-available, no
> license to activate or release a real optical circuit, and no memory
> across requests to prevent double-provisioning the same demand. A
> **separate governor**, backed by this actor's own ground-truth
> recomputation against the live network topology (via
> `kotoba-lang/apn`), earns it the right to commit.

## Scope

This actor operates at the **network-topology / provisioning-workflow**
layer. It is **not**:

- a physical-layer engineering tool. Whether a link *can* close optically
  (power budget, OSNR, amplifier placement) is out of scope -- that is
  `kotoba-lang/apn`'s own explicit non-goal too (see its README `Scope`),
  and one layer further down, a photonic-device/link-budget model such as
  `noroshi` (etzhayyim's photonics-electronics-convergence comms-chip
  actor).
- a vendor's control-plane implementation. No GMPLS/PCEP wire protocol,
  no NETCONF/gNMI device driver -- those are host-injected concerns this
  actor's `Store` protocol is written to accommodate later (see
  `netops.store`'s R0 SCOPE NOTE), not something it ships today.
- an IOWN/NTT-branded artefact -- see `kotoba-lang/apn`'s README for the
  same honest framing this actor inherits.

## Actuation

Every write passes through a **double gate**: the Network Provisioning
Governor (compliance -- can REJECT, never approve past its own HARD
checks) and the rollout phase gate (can further downgrade a clean commit
to a human-approval escalation). `:actuation/provision-lightpath` and
`:actuation/teardown-lightpath` -- activating or releasing a real optical
circuit -- are **never** in any phase's auto-commit set, at any phase.
Two independent layers (`netops.governor`'s high-stakes gate and
`netops.phase`'s permanently-absent membership) agree on this
structurally, not by convention.

```
  demand ---> [Network Advisor] ---> proposal
                                        |
                                        v
                          [Network Provisioning Governor]
                            HARD checks (unoverridable)
                                        |
                         ok?  ----------+----------  reject?
                          |                              |
                          v                              v
                   [phase gate]                   HOLD (ledger fact,
                   commit / escalate                no human turn)
                          |
             +------------+------------+
             |                         |
             v                         v
      auto-commit              human approval
      (demand/intake            (everything else,
       only, phase 3)        actuation is ALWAYS here)
             |                         |
             +------------+------------+
                          v
                    commit to SSoT
                    (apn.provision mutates
                     the live topology)
```

## Run

```bash
clojure -M:dev:run     # walk one clean demand through intake->license->screen->provision->teardown + four HARD-hold cases
clojure -M:dev:test    # governor contract · phase invariants · store · registry · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Open business

| Layer | What it is |
|---|---|
| **OSS core** (this repo) | The actor code: advisor, governor, phase gate, store, StateGraph wiring. AGPL-3.0-or-later. |
| **Business blueprint** (`blueprint.edn`) | The ISIC 6110 classification, social-impact tags, required/optional capability list. |
| **Operator playbook** (`docs/operator-guide.md`, `docs/business-model.md`) | How a real operator stands this up, staffs the human-approval seat, and prices the service. |
| **Trust controls** | Append-only audit ledger, unsigned draft certificates (see `netops.registry`), dedicated double-actuation-guard booleans. |

## Capability layer

`kotoba-lang/apn` supplies the pure topology model, structural validation,
and the RWA solver (`apn.rwa/assign`) the Network Advisor calls to draft
route-capacity screenings and provisioning proposals. This actor owns
none of that computation -- it wraps it with an LLM-drafted-proposal ⊣
governor actuation gate and an audit ledger, the same "the dependency,
not a deployment" split `kotoba-lang/apn`'s own README describes.

## Layout

| File | Role |
|---|---|
| `src/netops/store.cljc` | **Store** protocol -- `MemStore` (R0; see its ns docstring for the deferred `DatomicStore` scope note) + append-only audit ledger + the live `apn.model` network topology. |
| `src/netops/registry.cljc` | Lightpath-provisioning / lightpath-teardown draft records + the independent `route-endpoints-missing?` ground-truth check. |
| `src/netops/facts.cljc` | Per-jurisdiction right-of-way/carrier-license catalog with an official spec-basis citation per entry, honest coverage reporting. |
| `src/netops/advisor.cljc` | **Network Advisor** -- `mock-advisor` ‖ `llm-advisor`; drafts intake normalization, license-verification checklists, route-capacity screenings (by actually running `apn.rwa/assign`), and the two actuation proposals. |
| `src/netops/governor.cljc` | **Network Provisioning Governor** -- 4 HARD checks (spec-basis, evidence-incomplete, route-endpoints-invalid, capacity-blocked) + 2 double-actuation guards + 1 soft (confidence/actuation gate). |
| `src/netops/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised auto (demand/intake only; actuation never auto). |
| `src/netops/operation.cljc` | **OperationActor** -- langgraph StateGraph (intake→advise→govern→decide→[commit\|request-approval→commit\|hold]). |
| `src/netops/sim.cljc` | demo driver. |
| `test/netops/*_test.clj` | governor contract · phase invariants · store · registry conformance · facts coverage |

## Business-process coverage (honest)

Implemented: demand intake, right-of-way/carrier-license verification
(4 jurisdictions seeded), route-capacity screening via a real RWA solver,
lightpath provisioning, lightpath teardown, double-actuation prevention,
append-only audit ledger.

Not implemented (R0): a `DatomicStore` backend (MemStore only -- see
`netops.store`'s ns docstring), physical-layer link-budget/OSNR
validation (out of scope by design -- see `kotoba-lang/apn`'s README),
any real ROADM/EMS/NETCONF device integration, billing.

## Jurisdiction coverage (honest)

`netops.facts/catalog` seeds JPN / USA / GBR / DEU with an official
right-of-way/carrier-licensing spec-basis. `netops.facts/coverage`
reports any other requested jurisdiction as genuinely missing -- the
Network Advisor never fabricates one, and the Governor holds if it
tries.

## Maturity

`:implemented` (per `blueprint.edn`) -- actor code exists and its
governor contract is test-covered end-to-end, offline, deterministically.

## License

Code and implementation templates are AGPL-3.0-or-later.
