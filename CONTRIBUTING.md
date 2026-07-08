# Contributing

`cloud-itonami-isic-6110` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

The capability layer this actor wraps lives in
[`kotoba-lang/apn`](https://github.com/kotoba-lang/apn) (topology model
+ RWA solver). This repo holds the business blueprint, governor/phase
policy, and operator contracts.

```bash
clojure -M:dev:test    # this repo's own test suite
clojure -M:lint

# in kotoba-lang/apn:
clojure -M:test
clojure -M:lint
```

Keep changes small and include tests for governor checks, phase gating,
or `apn.rwa` routing/wavelength behavior (the latter in `kotoba-lang/apn`
itself, not here).

## Rules

- Do not commit real customer demand records, network telemetry,
  credentials, or circuit traffic data.
- Keep lightpath provisioning and teardown behind the Network
  Provisioning Governor.
- Treat network-operator workflows as high-risk: add tests for
  right-of-way/license evidence, route-capacity screening, and audit
  logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
