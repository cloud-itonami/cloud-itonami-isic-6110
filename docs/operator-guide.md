# Operator Guide

## Standing this up

1. `clojure -M:dev:test` -- confirm the governor contract passes in your
   checkout before touching anything.
2. Replace `netops.store/seed-db`'s demo topology and demand set with
   your real network (see `apn.model`'s README for the builder API) and
   real customer demand records.
3. Wire a real `Store` backend if you need persistence beyond a single
   process -- `netops.store`'s `MemStore` is dev/test/demo only (see its
   ns docstring). The `Store` protocol is written so this is additive.
4. Wire `netops.advisor/llm-advisor` with a real `langchain.model/
   ChatModel` if you want LLM-drafted proposals instead of the
   deterministic `mock-advisor`. The Governor's HARD checks apply
   identically either way -- an LLM hiccup degrades to a safe
   low-confidence noop, never an auto-commit.
5. Staff the human-approval seat. Every `:actuation/provision-
   lightpath`/`:actuation/teardown-lightpath` request pauses for a real
   person (`context :actor-id`) to approve via `(g/run* actor
   {:approval {:status :approved :by "..."}} {:thread-id tid :resume?
   true})` -- see `netops.sim` for the exact call shape.

## Operating a request lifecycle

```
:demand/intake  -> :license/verify -> :route/screen
                                             |
                                             v
                          :actuation/provision-lightpath (always human-approved)
                                             |
                                    ... circuit is live ...
                                             |
                                             v
                          :actuation/teardown-lightpath (always human-approved)
```

`:route/screen` is not a hard prerequisite the Governor enforces before
provisioning (see ADR-0001 "Alternatives considered") -- `apn.rwa/
assign` is re-run at commit time regardless, and a stale/missing screen
cannot silently succeed. Screening early is still good operator practice
so a customer-facing quote can be given before requesting actuation.

## Reading a HOLD

Every HOLD carries a `:basis` vector of rule keywords in the ledger
fact. See `netops.governor`'s ns docstring for the full check list and
what each one means operationally:

- `:no-spec-basis` -- the jurisdiction has no entry in `netops.facts` --
  add one with a real citation, never fabricate.
- `:evidence-incomplete` -- run `:license/verify` (and get it approved)
  before attempting actuation.
- `:route-endpoints-invalid` -- the demand's src/dst don't exist in your
  live topology -- fix the demand record or extend the topology.
- `:capacity-blocked` -- no free wavelength on any route -- add capacity
  (more channels, an alternate link) or wait for an existing lightpath
  to be torn down.
- `:already-provisioned` / `:already-torn-down` -- double-actuation
  guard tripped -- check whether the request is a genuine duplicate.

## Extending jurisdiction coverage

Add one map to `netops.facts/catalog` with a real
`:owner-authority`/`:legal-basis`/`:provenance`/`:required-evidence` --
never invent a jurisdiction's requirements to make coverage look bigger
(`netops.facts/coverage` reports honestly; a missing jurisdiction stays
missing until you add a cited entry).
