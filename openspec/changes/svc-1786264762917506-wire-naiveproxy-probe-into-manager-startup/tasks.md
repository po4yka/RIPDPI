# SVC-1786264762917506: Wire NaiveProxy helper probe into manager startup

## Objective

Wire NaiveProxy helper probe into manager startup

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] SVC-1786264762919787 (2026-05-15) Helper emits a single RIPDPI-PROBE { ... } JSON line on --probe exit with fields { "schemaversion": u32, "helperversion": semver, "features": [string, ...] }. Hand-formatted JSON (no serde dep for the fast-path) in ripdpi-naiv… #feature @item:SVC-1786264762917506
- [x] SVC-1786264762919579 (2026-05-28) Kotlin parser exists in NaiveProxyProbeParser.kt, with unit tests covering marker, malformed JSON, missing required fields, and schema-range checks #feature @item:SVC-1786264762917506
- [ ] SVC-1786264762919010 NaiveProxyManager invokes --probe before start, parses the JSON, and refuses to start when schemaversion is outside the range it supports, surfacing a recognizable failure class #feature @item:SVC-1786264762917506
- [ ] SVC-1786264762919278 Existing RIPDPI-READY / RIPDPI-ERROR paths remain unchanged for now; this task only adds the pre-launch probe #feature @item:SVC-1786264762917506
- [ ] SVC-1786264762919141 Unit tests cover manager preflight behavior: (a) probe round-trip, (b) refusal on schema mismatch, (c) backward compatibility when the helper does not support --probe if the current release still allows schema 0 #feature @item:SVC-1786264762917506
- [ ] SVC-1786264762919098 docs/native/relay-naiveproxy-runtime.md documents the probe line and the schema-version policy #feature @item:SVC-1786264762917506

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
