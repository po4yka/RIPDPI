---
id: SVC-1786264762917506
title: Wire NaiveProxy helper probe into manager startup
kind: feature
status: todo
area: service
priority: medium
risk: high
owner: Service runtime maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: svc-1786264762917506-wire-naiveproxy-probe-into-manager-startup
created: 2026-05-15
updated: 2026-08-09
---

## Summary

The helper-side `--probe` line and Kotlin parser now exist. Finish the Android startup integration by invoking `--probe` before launch, rejecting unsupported schema versions, and documenting the enforced policy.

## Context

`native/rust/crates/ripdpi-naiveproxy/src/main.rs` emits `RIPDPI-PROBE { ... }` on `--probe`, and `core/service/src/main/kotlin/com/poyka/ripdpi/services/NaiveProxyProbeParser.kt` parses it. `NaiveProxyManager` still starts the helper without running that probe, so schema drift can still reach runtime launch.

## Acceptance criteria

- [x] (2026-05-15) Helper emits a single `RIPDPI-PROBE { ... }` JSON line on `--probe` exit with fields `{ "schema_version": u32, "helper_version": semver, "features": [string, ...] }`. Hand-formatted JSON (no serde dep for the fast-path) in `ripdpi-naiveproxy/src/main.rs`. Two unit tests assert format and capability-tag stability.
- [x] (2026-05-28) Kotlin parser exists in `NaiveProxyProbeParser.kt`, with unit tests covering marker, malformed JSON, missing required fields, and schema-range checks.
- [ ] `NaiveProxyManager` invokes `--probe` before `start`, parses the JSON, and refuses to start when `schema_version` is outside the range it supports, surfacing a recognizable failure class.
- [ ] Existing `RIPDPI-READY` / `RIPDPI-ERROR` paths remain unchanged for now; this task only adds the pre-launch probe.
- [ ] Unit tests cover manager preflight behavior: probe round-trip, refusal on exact schema mismatch, missing required capability tags, and a recognizable configuration error before runtime launch. Legacy schema-0 fallback is not supported.
- [ ] `docs/native/relay-naiveproxy-runtime.md` documents the probe line and the schema-version policy.

## Definition of done

- Probe is invoked at every helper start in service code.
- Schema-mismatch failures are visible in service telemetry and not conflated with subprocess crashes.

## Risks / open questions

- The manager and bundled helper ship together, so accepting schema 0 would hide packaging drift; preflight must fail closed.

## Links

- [[relay-naiveproxy-runtime]]

## Work log

- 2026-06-05: First two criteria verified done (Rust probe emission in ripdpi-naiveproxy/src/main.rs with 2 tests; NaiveProxyProbeParser.kt with full parser tests); NaiveProxyManager.start() has no --probe call, no manager-preflight tests, and docs/native/relay-naiveproxy-runtime.md explicitly notes probe is not yet enforced — 4 criteria remain open.
