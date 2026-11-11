---
id: TRN-1794383445146007
title: Repair WebSocket and raw Upgrade contracts
kind: bug
status: doing
area: transport
priority: high
owner: transport-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-websocket-upgrade-contracts
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Repair confirmed TRN-09 transport defects without adding an outbound backend.

## Acceptance criteria

- Reject unrequested WebSocket extensions, extra Upgrade values, and repeated singleton response fields; accept valid Connection token lists.
- Preserve relay errors and complete peer Close replies before EOF.
- Validate raw Upgrade requests and responses, including interim responses and retained payload.
- Keep TLS identity checks and bounded buffering. Confirm or bound the recorded privacy and resource risks.
- Pass owning Rust tests, review, source gates, and combined integration checks.
- Commit each confirmed defect separately, integrate into main, and push origin/main.

## Parallel ownership

- transport-integration: dependency verifier, vendor/tungstenite, Cargo.toml, Cargo.lock, planning and evidence.
- relay-errors: ripdpi-ws-tunnel/src/relay.rs and src/relay/**; inspect relay shutdown only within this ownership.
- raw-upgrade: ripdpi-ws-tunnel/src/httpupgrade.rs and owning tests; six-finding F4/F6 commits remain separate.
- close-driver: ripdpi-ws-tunnel/src/transport.rs, ripdpi-wireguard-ws/** and ripdpi-warp-core/src/wireguard/carrier.rs plus owning tests.
- Shared serialized files, snapshots, and all planning records have one writer: transport-integration. Workers do not change them.
- Each writer uses its assigned worktree. Review agents are read-only.
