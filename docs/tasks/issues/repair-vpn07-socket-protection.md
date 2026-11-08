---
id: VPN-1794152756242597
title: Repair outbound socket protection from VPN-07 audit
kind: bug
status: doing
area: vpn
priority: high
owner: VPN protection maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn07-socket-protection
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Protect every in-scope VPN outbound socket before routing decisions, bound protection requests, and keep protection ownership consistent through stop and failure. Repair findings F1-F6 from the VPN-07 audit without changing RAW_PATH or inactive proxy behavior.

## Acceptance criteria

- F1: UI runtime protection reaches every proxy TCP/UDP socket setting; denial creates no outbound connection.
- F2: Subprocess and WebSocket UDS connect, send, and ACK waits are bounded.
- F3: Stop outcomes and failures preserve pending native ownership and run required cleanup without stale-session interference.
- F4: Naive profile checks use the captured active VPN protection lease.
- F5: All listed UDP factories protect before bind, including replacement sockets.
- F6: Stalled UDS handshakes expire and release handlers; transferred fds close on error.
- Targeted regressions, affected-module checks, independent review, and combined offline CI gates pass or have exact observed blockers.
- Each finding has a separate fix commit integrated into main and pushed to origin.

## Parallel ownership

- Native worker: F1 proxy config/runtime projections and tests; F2 subprocess-protect, ws-tunnel protect clients and tests.
- Lifecycle worker: F3 service session cleanup/lifecycle and their tests; F4 Naive profile checker/configuration and their tests.
- Server worker: F6 protect server/client-session/dispatcher and tests; verify H3 ancillary-fd exception ownership in this same area.
- Primary: F5 UDP factories and tests; portfolio/OpenSpec/docs, integration, gates and commits.
- Serialized files: primary alone owns task records, OpenSpec artifacts and generated board. No worker changes Cargo.lock, dependency versions, schemas, locale sets, baselines, snapshots or golden fixtures.
- Each writer uses its named worktree. Workers do not commit before independent review and do not push or edit main. Changes from other writers must be preserved.
