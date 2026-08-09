# SVC-1786264762917506: Wire NaiveProxy helper probe into manager startup

## Objective

Require an exact helper capability/schema preflight before every NaiveProxy runtime start.

## Ownership

- `core/service` NaiveProxy manager, parser, telemetry, and tests
- bundled `ripdpi-naiveproxy` helper probe contract and runtime documentation

## Execution

- [x] SVC-1786264762919787 Emit the versioned RIPDPI-PROBE helper record #feature @item:SVC-1786264762917506
- [x] SVC-1786264762919579 Parse and validate the probe record in Kotlin #feature @item:SVC-1786264762917506
- [ ] SVC-1786264762919010 Invoke --probe before start and fail with a typed configuration error on schema or capability mismatch #feature @item:SVC-1786264762917506
- [ ] SVC-1786264762919278 Preserve the existing RIPDPI-READY and RIPDPI-ERROR runtime protocol after successful preflight #feature @item:SVC-1786264762917506 @blocked_by:SVC-1786264762919010
- [ ] SVC-1786264762919141 Cover exact schema, required capabilities, malformed output, timeout, and missing-probe fail-closed paths #feature @item:SVC-1786264762917506 @blocked_by:SVC-1786264762919010
- [ ] SVC-1786264762919098 Document the enforced preflight and schema policy in relay-naiveproxy-runtime.md #feature @item:SVC-1786264762917506 @blocked_by:SVC-1786264762919141

## Verification

- focused `NaiveProxyManager` and `NaiveProxyProbeParser` unit tests
- `cargo nextest -p ripdpi-naiveproxy`
- service lifecycle regression tests and documentation contract check
