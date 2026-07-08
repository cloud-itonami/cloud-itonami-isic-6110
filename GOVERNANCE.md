# Governance

`cloud-itonami-isic-6110` is an OSS open-business blueprint for wired
telecommunications network operations. Governance covers both the
capability layer and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- a lightpath cannot be provisioned or torn down without right-of-way/
  carrier-license verification and Network Provisioning Governor
  approval.
- the Network Provisioning Governor remains independent of the advisor.
- hard policy violations (fabricated jurisdiction spec-basis, a
  structurally invalid route endpoint, a capacity-blocked route,
  double-actuation) cannot be overridden by human approval.
- every intake, verify, screen, provision and teardown path is
  auditable.
- customer circuit demand data and real network telemetry stay outside
  Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification
is a separate trust mark and should require security, audit, and
network-capacity/right-of-way compliance review.

Certified operators can lose certification for:

- bypassing provisioning or teardown policy checks
- mishandling customer circuit or network topology data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
