# Security Policy

This project handles network-provisioning workflows for a wired
telecommunications carrier -- lightpath activation/teardown authority,
customer circuit demand records, and jurisdiction license evidence.
Treat vulnerabilities as potentially high impact even when the demo data
and demo topology are synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real customer/demand data exposure
- authorization bypass
- Network Provisioning Governor bypass
- audit-ledger tampering
- unauthorized lightpath activation/teardown (actuation bypass)
- over-disclosure in reports or exports

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on customer data, governor enforcement, or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real customer/circuit data outside this repository.
- Run the governor contract tests before deployment.
- Export and review the audit ledger regularly.
- Use least privilege for operators and service accounts.
- Treat `netops.store`'s `MemStore` as a dev/test/demo backend only --
  it holds all state in a single process atom with no persistence or
  access control (see its ns docstring's R0 SCOPE NOTE).
