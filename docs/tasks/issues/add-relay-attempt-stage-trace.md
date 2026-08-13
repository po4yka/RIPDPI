---
id: DGN-1786592449526581
title: Add privacy-safe relay attempt stage trace
kind: feature
status: doing
area: diagnostics
priority: high
risk: high
owner: Relay diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: add-relay-attempt-stage-trace
created: 2026-08-13
updated: 2026-08-13
related_tasks: []
---

## Goal

Persist a bounded, privacy-safe, per-attempt stage trace for VLESS Reality relay
connections so an exported diagnostic archive identifies the last completed
protocol stage and the exact stage that failed without relying on free-form
Logcat text.

## Ownership

- Native relay/VLESS attempt-stage emission and the bounded native event ring.
- Rust-to-Kotlin runtime telemetry projection, persistence, redaction, and
  diagnostic archive export.
- Serialized telemetry fields and archive fixtures are single-writer lanes for
  this task.

## Acceptance criteria

- Every VLESS Reality TCP relay attempt has an opaque attempt identifier and a
  monotonically ordered trace covering TCP connect, Reality TLS, VLESS request,
  first validated VLESS response, SOCKS result, and terminal close/failure when
  those stages are reached.
- Stage records carry typed outcome, duration, failure stage/class, I/O kind,
  errno, and peer-close phase where evidence exists; unknown evidence remains
  explicitly absent rather than inferred.
- The trace crosses the native telemetry boundary, persists with its owning
  connection session, and is exported as structured redacted archive evidence.
- Existing relay lifecycle events are persisted in live and terminal paths and
  therefore appear in the current redacted `native-events.csv` export before
  typed attempt-stage fields are introduced.
- `runtime-config.json` exports a versioned fingerprint of the effective
  allowlisted strategy projection so archives can be compared without exposing
  the projection, raw runtime JSON, endpoints, network identifiers, or
  credentials.
- Collection is bounded and non-blocking, emits no per-packet events, and never
  includes UUIDs, credentials, raw endpoints, ClientHello bytes, payloads, or
  device/network identifiers.
- Focused Rust/Kotlin contract, persistence, redaction, and archive tests pass;
  static analysis and affected locked Cargo gates pass.
