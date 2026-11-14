---
id: ENG-1794637907795927
title: Correct DPI-24 resource and response contracts
kind: bug
status: review
area: engine
priority: high
owner: DPI-24 remediation
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi24-audit-remediation
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Correct DPI-24 resource limits, response accounting, and presentation without changing saved IDs or packet bytes.

## Acceptance criteria

- Reject DPI-24 count and aggregate repeats above 64 in Kotlin/Rust; bound internal planning before copies.
- Correlate QUIC success with valid Initial/Retry. Preserve transparent generic UDP forwarding and timeout behavior.
- Reproduce and correct early probe completion on unrelated replies within one original deadline.
- Correct all displayed names/documents while retaining IDs and old report aliases.
- One fix commit per confirmed problem, observed tests, required gates, independent review, integration and push.

## Parallel ownership

- limits: ripdpi-config UDP limits; ripdpi-proxy-config UDP admission/tests; ripdpi-desync UDP admission/tests; core/data/model validation/tests. DPI-24 only.
- correlation: ripdpi-proxy-runtime UDP session/receive/feedback/tests; ripdpi-proxy-runtime-desync-adapter semantic helpers; diagnostics-runner/transport filtered QUIC receive/tests; packet response helpers only if needed. F2 and confirmed H1 are separate commit slices.
- labels: all ten locale sets for existing DPI-24 keys; Kotlin display labels/aliases/tests; diagnostics-candidates labels/tests; packetizer and QUIC taxonomy documents.
- integrator: task/OpenSpec, generated API snapshots/exports, combined gates, independent reviewer, commits/integration/push.
- Serialized lanes: locales only labels; snapshots only integrator. Dependencies, Cargo.lock, schemas, baselines and goldens unchanged.
