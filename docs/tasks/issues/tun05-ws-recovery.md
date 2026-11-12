---
id: SVC-1794477234440384
title: Recover established WireGuard WebSocket sessions
kind: bug
status: done
area: service
priority: high
owner: TUN-05 recovery worker
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-ws-recovery
created: 2026-11-12
updated: 2026-11-12
closed_at: "2026-11-12T11:09:32Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Source 38b8f4bac6 matches the verified application tree: 2288 Kotlin and 234 Rust tests, staticAnalysis, ci-gates and independent reviews passed. Device and external acceptance are not claimed."
---

## Goal

Recover an established WS carrier with a fresh authenticated native session without removing the VPN barrier.

## Acceptance criteria

- Retry only established WS sessions with bounded backoff; preserve UDP fail-stop.
- Stop and replacement sessions cancel recovery and reject stale callbacks.
- Targeted supervisor regressions and source gates pass.

## Ownership

Recovery worker owns the AWG supervisor and its tests. The readiness worker owns the timeout helper and its single awaitReady call-site edit before this worker rebases. No locale, Cargo.lock, or dependency changes.
