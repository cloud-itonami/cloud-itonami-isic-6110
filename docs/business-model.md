# Business Model

## What this is

An open-source, forkable business blueprint for a **wired
telecommunications network operator** (ISIC Rev.5 6110): a carrier that
provisions and operates optical circuits (lightpaths) for enterprise/
carrier-of-carrier customers over its own or leased fibre.

## Revenue

- **Lightpath provisioning fees** -- one-time activation charge per
  circuit.
- **Recurring capacity fees** -- monthly/annual charge for the duration
  a lightpath stays active (billed outside this repo's scope; see
  `docs/operator-guide.md`).
- **Wholesale wavelength leasing** -- selling point-to-point capacity to
  other carriers ("carrier-of-carriers"), the same commercial pattern as
  a dark-fibre or wavelength-services business.

## Cost structure an operator brings

- Physical fibre plant and ROADM/photonic-node hardware (not part of
  this repo -- `kotoba-lang/apn`'s `apn.model` describes a topology, it
  does not build one).
- The human network-operations seat that approves every
  `:actuation/provision-lightpath`/`:actuation/teardown-lightpath`
  request (never automated, by design -- see README `Actuation`).
- Right-of-way/carrier-license compliance overhead per jurisdiction
  (`netops.facts`).

## Trust controls this repo provides

- An independent Network Provisioning Governor that can reject a
  proposal outright (HARD checks) or force human review (SOFT gate) --
  the LLM advisor never has unilateral write access.
- An append-only audit ledger recording every decision, commit or hold.
- Unsigned draft certificates for every provisioning/teardown record
  (`netops.registry`) -- the operator's own signing act, not this
  actor's, finalizes them.
- Structural double-actuation prevention (a demand's lightpath cannot be
  provisioned twice, or torn down twice).

## What a forking operator must add

- A real `Store` backend beyond the in-memory default (see `netops.
  store`'s R0 scope note -- a `DatomicStore` or equivalent).
- Real ROADM/EMS/NETCONF device integration behind `apn.provision`'s
  pure state transitions (this repo's topology mutations are in-memory
  only; making them real requires a host-injected device driver, out of
  scope by design -- see README `Scope`).
- Billing/invoicing (out of scope; this repo governs *provisioning*, not
  payment).
- Jurisdiction coverage beyond the four seeded in `netops.facts`
  (JPN/USA/GBR/DEU) -- additive, never fabricated.
