---
title: Make NaiveProxy helper probe return structured version JSON
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Make NaiveProxy helper probe return structured version JSON #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `make-naiveproxy-helper-probe-return-structured-version-json`
- **Verify:** `cargo test -p ripdpi-naiveproxy && ./gradlew :core:service:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-naiveproxy/**`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/NaiveProxyManager.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/SubprocessSocksRelayManager.kt`, `docs/native/relay-naiveproxy-runtime.md`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Replace the implicit `RIPDPI-READY` / `RIPDPI-ERROR` text contract between Android and the NaiveProxy helper with a structured probe line that carries the helper's schema version, NaiveProxy version, and feature set, and refuse to start the helper on mismatch.

## Context

`docs/native/relay-naiveproxy-runtime.md` notes "helper version probing before launch" but does not specify the wire contract. Today the service-side classification (DNS, TLS, HTTP CONNECT, auth) is built on parsing free-form lines. If `ripdpi-naiveproxy` adds or renames a flag or readiness signal, `NaiveProxyManager` may silently misclassify or hang.

## Acceptance criteria

- [x] (2026-05-15) Helper emits a single `RIPDPI-PROBE { ... }` JSON line on `--probe` exit with fields `{ "schema_version": u32, "helper_version": semver, "features": [string, ...] }`. Hand-formatted JSON (no serde dep for the fast-path) in `ripdpi-naiveproxy/src/main.rs`. Two unit tests assert format and capability-tag stability.
- [ ] `NaiveProxyManager` invokes `--probe` before `start`, parses the JSON, and refuses to start when `schema_version` is outside the range it supports, surfacing a recognizable failure class.
- [ ] Existing `RIPDPI-READY` / `RIPDPI-ERROR` paths remain unchanged for now; this task only adds the pre-launch probe.
- [ ] Unit tests cover (a) probe round-trip, (b) refusal on schema mismatch, (c) backward compatibility when the helper does not support `--probe` (treat as schema 0, accept until the next release).
- [ ] `docs/native/relay-naiveproxy-runtime.md` documents the probe line and the schema-version policy.

## Definition of done

- Probe is invoked at every helper start in service code.
- Schema-mismatch failures are visible in service telemetry and not conflated with subprocess crashes.

## Risks / open questions

- Schema-0 fallback gives the helper one release of grace; after that the manager should hard-require the probe. Decide if a build flag controls the cutoff.

## Links

- [[relay-naiveproxy-runtime]]
