---
id: SVC-1794477234440384
title: Recover established WireGuard WebSocket sessions
kind: bug
status: review
area: service
priority: high
owner: TUN-05 recovery worker
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-ws-recovery
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Recover an established WS carrier with a fresh authenticated native session without removing the VPN barrier.

## Acceptance criteria

- Retry only established WS sessions with bounded backoff; preserve UDP fail-stop.
- Stop and replacement sessions cancel recovery and reject stale callbacks.
- Targeted supervisor regressions and source gates pass.

## Ownership

Recovery worker owns the AWG supervisor and its tests. The readiness worker owns the timeout helper and its single awaitReady call-site edit before this worker rebases. No locale, Cargo.lock, or dependency changes.
