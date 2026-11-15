---
id: DGN-1794734098746391
title: Correct QUIC Initial connectivity evidence and transport
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia07-quic-initial-probe
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct the first-flight QUIC evidence and UDP transport.

## Acceptance criteria

Cover the eight DIA-07 findings with small commits, focused packet and loopback regressions, Kotlin projections, and the combined CI source gates. Verify integration into main and push to origin.

## Parallel ownership

- Packet writer owns ripdpi-packets probe and response files, packet tests, and the packet API snapshot.
- Transport writer owns ripdpi-diagnostics-transport and its API snapshot. No other writer edits transport files.
- Kotlin writer owns core/diagnostics and app consumers. The integrator owns the Rust side of the shared observation contract.
- Integrator owns native runner, monitor request and scheduler, OpenSpec, portfolio, and all remaining generated API and contract snapshots. No dependency updates, goldens or baselines are assigned.
