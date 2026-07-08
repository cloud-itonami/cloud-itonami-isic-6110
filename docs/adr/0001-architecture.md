# ADR-0001: Network Advisor ⊣ Network Provisioning Governor architecture

## Status

Accepted. `cloud-itonami-isic-6110` published directly at `:implemented`
in the `kotoba-lang/industry` registry (see the superproject-level
`90-docs/adr/` entry for the registry-promotion decision this ADR is
paired with).

## Context

`cloud-itonami-isic-6110` publishes an OSS business blueprint for a
wired-telecommunications network operator: circuit-demand intake,
right-of-way/carrier-license verification, route-capacity screening, and
lightpath provisioning/teardown, run by a qualified operator so a
community keeps its own network topology and provisioning records. Like
every prior actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0→3 rollout pattern
established across this fleet, most directly mirroring
`cloud-itonami-isic-6190` (other telecommunications: line intake /
billing-dispute screening / number provisioning) op-for-op.

The distinguishing feature of this actor, relative to `isic-6190`, is
that it wraps a genuine domain-computation library --
[`kotoba-lang/apn`](https://github.com/kotoba-lang/apn), a pure
All-Photonics-Network topology model and Routing-and-Wavelength-
Assignment (RWA) solver -- rather than reasoning only over flat record
fields. The Network Advisor's route-capacity screening actually runs
`apn.rwa/assign` against the live network topology; the Governor's
`route-endpoints-invalid-violations` check actually recomputes node
existence against that same live topology. Neither is a self-report.

## Decision

### Decision 1: entity and op shape

The primary entity is a `demand` (a customer circuit request, analogous
to `telecom.store`'s `line`). Five ops: `:demand/intake` (directory
upsert, no capital risk), `:license/verify` (per-jurisdiction right-of-
way/carrier-license evidence checklist, never auto), `:route/screen`
(route-capacity screening via `apn.rwa/assign`, unconditional-
evaluation discipline, never auto), `:actuation/provision-lightpath`
(POSITIVE, high-stakes), and `:actuation/teardown-lightpath` (NEGATIVE,
high-stakes). This is the same dual-actuation-on-one-entity shape
`telecom`/`water`/`leasing`/`card` and other siblings use.

### Decision 2: `route-endpoints-missing?` -- a network-topology instance of the format/syntactic-validity check family

`netops.registry/route-endpoints-missing?` independently recomputes
whether a demand's own recorded src/dst nodes exist in the LIVE network
topology, gating only `:actuation/provision-lightpath` (the point where
a phantom route would otherwise get provisioned for real). Unlike
`telecom.registry/e164-invalid-format?` (a pure recompute on the line's
own field, no store lookup), this check needs one additional lookup --
the live topology -- because "does this node exist" is a property of
the network, not of the demand record alone. It plays the same
architectural role: a ground-truth recompute independent of the
advisor's self-reported confidence.

### Decision 3: `capacity-blocked-violations` is scoped to provisioning, not teardown

Unlike `telecom.governor`'s `billing-dispute-unresolved-violations`
(scoped to `:billing/screen`/`:actuation/suppress-billing-record`, i.e.
the SUPPRESSION side), this actor's unconditional capacity-blocked
check is scoped to `:route/screen`/`:actuation/provision-lightpath` --
the PROVISIONING side. This is a deliberate domain adaptation, not a
mechanical copy: a demand should not be provisioned through a route the
network cannot currently support, but releasing (tearing down) an
already-active lightpath never needs a capacity check -- teardown frees
resources, it does not consume them.

### Decision 4: `Store` protocol, `MemStore` only (R0)

Unlike `telecom.store` (which ships both `MemStore` and a `DatomicStore`
proven to satisfy the same contract), this actor ships `MemStore` only.
The `Store` protocol is written so a `DatomicStore` can be added later
without touching the governor, advisor or StateGraph, but writing and
proving a second backend against the same contract test is deliberately
deferred rather than shipped unverified -- see `netops.store`'s own ns
docstring for the explicit scope note. This is a genuine coverage gap
relative to the sibling pattern, recorded honestly rather than silently.

### Decision 5: dedicated double-actuation-guard booleans

`:lightpath-provisioned?`/`:lightpath-torn-down?` are dedicated booleans
on the `demand` record, never a single `:status` value -- the same
discipline every prior sibling governor's guards establish.

### Decision 6: Phase 0→3 rollout

Phase 3's `:auto` set has exactly one member, `:demand/intake` (no
capital risk). `:license/verify` and `:route/screen` are never
auto-eligible at any phase, and `:actuation/provision-lightpath`/
`:actuation/teardown-lightpath` are permanently excluded from every
phase's `:auto` set -- a structural fact, enforced by both `netops.
phase` and `netops.governor`'s `high-stakes` set independently.

### Decision 7: a demand's own id is its lightpath id

`netops.store`'s `provision-lightpath!`/`teardown-lightpath!` call
`apn.provision/request`/`apn.provision/teardown` using the demand's own
`:id` as the apn lightpath id -- no separate mapping table between
"demand" and "lightpath" is needed, since there is exactly one lightpath
per demand in this actor's model.

### Decision 8: mock + LLM advisor pair

`netops.advisor` provides `mock-advisor` (deterministic, default
everywhere -- the actor graph and governor contract run offline) and
`llm-advisor` (backed by `langchain.model/ChatModel`, with a defensive
EDN-proposal parser so a malformed LLM response degrades to a safe
low-confidence noop rather than ever auto-provisioning or
auto-tearing-down a lightpath).

## Alternatives considered

- **A single actuation (provisioning only), treating teardown as a
  lower-stakes administrative note.** Rejected: releasing an active
  optical circuit is a real capital/service-continuity act for the
  customer on the other end -- the same reasoning `telecom`/`water`'s
  negative-actuation precedent already established for this fleet.
- **Requiring a clean `:route/screen` on file before
  `:actuation/provision-lightpath` can proceed (an evidence-style
  gate).** Rejected for R0: `apn.rwa/assign` is re-run at commit time
  inside `netops.store/provision-lightpath!` regardless (and throws if
  it can no longer find a path/wavelength), so a stale or missing prior
  screening cannot silently succeed -- adding a second gate would be
  redundant with that commit-time recheck, not additional safety.
- **A `DatomicStore` backend from day one, matching every prior
  sibling.** Rejected for R0 in favor of an honestly-scoped `MemStore`-
  only ship plus a documented seam for later extension -- see Decision
  4.

## Consequences

- Confirms the "wrap a pure domain-computation library, don't
  reimplement its algorithm inline" pattern generalizes: the Advisor
  and Governor both call into `kotoba-lang/apn` rather than
  reimplementing routing/wavelength logic themselves.
- The `MemStore`-only scope (Decision 4) is a known, documented gap
  relative to the sibling pattern -- a `DatomicStore` follow-up is
  additive, not a rewrite, when undertaken.
- `route-endpoints-missing?`/`capacity-blocked-violations`'s
  provisioning-only scoping (Decisions 2-3) demonstrate that mirroring
  a sibling's architecture does not mean copying its exact check
  semantics verbatim -- each check's scope was re-derived from this
  actor's own domain, not assumed from `telecom.governor`.
