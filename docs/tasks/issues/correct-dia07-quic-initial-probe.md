---
id: DGN-1794734098746391
title: Correct QUIC Initial connectivity evidence and transport
kind: bug
status: done
area: diagnostics
priority: high
owner: Diagnostics repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia07-quic-initial-probe
created: 2026-11-15
updated: 2026-11-19
status_detail: Local validation passed; integration CI remains required.
closed_at: "2026-11-19T10:37:49Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Local Rust packet, transport, runner and monitor suites, 105 JVM tests and just ci-gates passed at 4fc28312d. CI run 44246104060 passed the Rust workspace tests and run 44369551319 passed the Kotlin, Android build and instrumented lanes; both contain 4fc28312d.
---

## Goal

Correct the first-flight QUIC evidence and UDP transport.

## Acceptance criteria

Cover the eight DIA-07 findings with small commits, focused packet and loopback regressions, Kotlin projections, and the combined CI source gates. Verify integration into main and push to origin.

## Parallel ownership

- Packet writer owns ripdpi-packets probe and response files, packet tests, and the packet API snapshot.
- Transport writer owns ripdpi-diagnostics-transport and its API snapshot. No other writer edits transport files.
- Kotlin writer owns core/diagnostics, Android local-network admission in core/data/settings, and app consumers. The integrator owns the Rust side of the shared observation contract.
- Integrator owns native runner, monitor request and scheduler, OpenSpec, portfolio, and all remaining generated API and contract snapshots. No dependency updates, goldens or baselines are assigned.
