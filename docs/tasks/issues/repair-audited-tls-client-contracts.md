---
id: TRN-1794158908788387
title: Repair audited TLS client contracts
kind: bug
status: review
area: transport
priority: high
owner: tls-client-audit
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-audited-tls-client-contracts
created: 2026-11-08
updated: 2026-11-08
status_detail: Seven finding commits are complete. Rebased ten-package tests, independent reviews and local gates passed; exact-source hosted CI remains unobserved.
---

## Goal

Repair the seven confirmed STD-01 TLS client findings with one code commit per finding. Keep intentional trust modes and protocol version limits explicit.

## Acceptance criteria

- TLS diagnostics keep encryption on nonstandard ports and stop on deadline or cancellation.
- DoT enforces configured pins and a DNS-owned ALPN policy.
- A DoQ query timeout does not close other query streams.
- MASQUE H2 and xHTTP require negotiated h2 before application bytes.
- TUIC does not advertise or silently accept a nonfunctional early-data path.
- Each repair has an observed regression, relevant local tests, independent review, and a separate commit.
- Combined ci-gates and affected Rust checks pass before main integration and push.

## Ownership and serialized lanes

- tls-dns worker: ripdpi-dns-resolver source, tests and owning documentation; findings F02, F03, F05.
- tls-relay worker: ripdpi-masque, ripdpi-xhttp, the narrow ripdpi-vless REALITY ALPN accessor, and ripdpi-tuic source/tests; findings F04 and F07. Own narrow relay-core TUIC runtime validation and tests for direct and chain paths; coordinate Kotlin validation before changing it.
- tls-diagnostics worker: ripdpi-diagnostics-tls, ripdpi-diagnostics-fat-header and monitor-engine connection-concurrency source/tests; findings F01 and F06.
- tls-client-audit integrator: planning and task files, combined validation, API snapshot updates when required, integration and push.
- No parallel writer may edit Cargo.lock, dependency manifests, locale resources, baselines or generated board. The integrator owns required shared changes.
- Each writer uses a separate worktree. Review agents are read-only. No device, public-server, real-secret or release operation is in scope.
