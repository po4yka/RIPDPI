---
id: SVC-1786264762917506
title: Wire NaiveProxy helper probe into manager startup
kind: feature
status: review
area: service
priority: medium
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1786264762917506-wire-naiveproxy-probe-into-manager-startup
created: 2026-05-15
updated: 2026-08-30
status_detail: Implementation and local verification complete; remote CI intentionally not monitored and device evidence remains unavailable.
---

## Summary

The Android service now requires the helper's `--probe` result before every NaiveProxy launch, rejects incompatible schemas, and reports compatibility failures separately from subprocess crashes.

## Context

`native/rust/crates/ripdpi-naiveproxy/src/main.rs` emits `RIPDPI-PROBE { ... }` on `--probe`, and `NaiveProxyManager` validates the parsed schema before handing the exact probed binary to the existing launch path.

## Acceptance criteria

- [x] (2026-05-15) Helper emits a single `RIPDPI-PROBE { ... }` JSON line on `--probe` exit with fields `{ "schema_version": u32, "helper_version": semver, "features": [string, ...] }`. Hand-formatted JSON (no serde dep for the fast-path) in `ripdpi-naiveproxy/src/main.rs`. Two unit tests assert format and capability-tag stability.
- [x] (2026-05-28) Kotlin parser exists in `NaiveProxyProbeParser.kt`, with unit tests covering marker, malformed JSON, missing required fields, and schema-range checks.
- [x] (2026-08-30) `NaiveProxyManager` invokes `--probe` before `start`, parses the JSON, and refuses to start when `schema_version` is outside the supported schema-1 range, surfacing `relay_compatibility` telemetry.
- [x] (2026-08-30) Existing `RIPDPI-READY` / `RIPDPI-ERROR` paths remain unchanged; successful preflight delegates to the prior launch and readiness pipeline.
- [x] (2026-08-30) Unit tests cover probe round-trip, schema mismatch, missing probe support, exact-binary launch, repeated starts, timeout, cancellation, forced termination, and compatibility telemetry. Schema 0 is intentionally unsupported because each start extracts the bundled helper from the current APK.
- [x] (2026-08-30) `docs/native/relay-naiveproxy-runtime.md` documents the probe line and schema-version policy.

## Definition of done

- Probe is invoked at every helper start in service code.
- Schema-mismatch failures are visible in service telemetry and not conflated with subprocess crashes.

## Risks / open questions

- Schema-0 fallback is intentionally absent: extraction refreshes the helper from the current APK before each start, so missing probe support is packaging drift rather than a valid older installed runtime.

## Links

- [[relay-naiveproxy-runtime]]

## Work log

- 2026-06-05: First two criteria verified done (Rust probe emission in ripdpi-naiveproxy/src/main.rs with 2 tests; NaiveProxyProbeParser.kt with full parser tests); NaiveProxyManager.start() has no --probe call, no manager-preflight tests, and docs/native/relay-naiveproxy-runtime.md explicitly notes probe is not yet enforced — 4 criteria remain open.
- 2026-08-30: Mandatory schema-1 preflight, exact-artifact launch, compatibility telemetry, cancellation-safe process cleanup, tests, and runtime documentation completed in `304d8a3b8`.
