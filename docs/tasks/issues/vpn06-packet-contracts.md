---
id: VPN-1794152786002343
title: Repair IPv4 and IPv6 TUN packet contracts
kind: bug
status: review
area: vpn
priority: high
owner: VPN packet team
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn06-packet-contracts
created: 2026-11-08
updated: 2026-11-08
status_detail: F1-F8 repaired and independently reviewed; 547 host tests, Clippy and ci-gates passed. Android, privileged raw and external compatibility are not claimed.
---

## Goal

Repair findings F1-F8 from the VPN-06 audit. Reject invalid packets before forwarding and preserve valid IPv4/IPv6 TCP and UDP behavior.

## Acceptance criteria

- Keep one fix commit for each finding and a regression test with an observed failing run.
- Pass affected Rust tests, formatting, Clippy and just ci-gates on the combined tree.
- Complete independent review before integration. Record host, Android, CI and external evidence separately.
- Integrate into main and push origin/main as requested.

## Ownership

- Core validation writer: ripdpi-tunnel-core/src/classify*, io_loop/routing.rs, packet validation and normalization helpers, related tests (F1/F2/F5).
- Reassembly writer: ripdpi-tunnel-core/src/io_loop/defrag.rs and defrag/ tests/helpers only (F3/F4 and bounded lifetime).
- Intercept writer: ripdpi-tunnel-intercept/src/** only (F6/F7/F8 and extension-aware low TTL).
- Integrator: task/OpenSpec artifacts, tunnel-core packet_write.rs, packet-level integration tests and combined gates.
- Each writer uses a separate worktree. Cargo.lock, Cargo.toml, vendor/smoltcp, protobuf/JNI contracts, baselines and golden files are serialized to the integrator; no edits are planned.
