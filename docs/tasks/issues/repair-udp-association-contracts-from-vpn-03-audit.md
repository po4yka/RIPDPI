---
id: VPN-1794141873537976
title: Repair UDP association contracts from VPN-03 audit
kind: bug
status: review
area: vpn
priority: high
owner: VPN repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn03-udp-association-repairs
created: 2026-11-08
updated: 2026-11-08
status_detail: All repair steps, host regression tests and local gates passed; remote CI evidence remains pending.
---

## Goal

Repair the five confirmed VPN-03 findings with independent regression evidence and separate implementation commits. Verify hypotheses before making further changes.

## Acceptance criteria

- Compatible native and Xray UDP handoff preserves resolved address and response identity.
- Accepted destinations keep reply mappings; historical mappings cannot occupy the shared DNS cache indefinitely.
- Normal large UDP replies preserve boundaries and fit the configured IPv4/IPv6 TUN MTU.
- Bad UDP checksums are dropped before association admission.
- Local regression tests, applicable lint and source gates are observed. Review findings are resolved.
- Each repair is committed separately, integrated into main, and pushed to origin. Device and external compatibility gaps remain explicit.

## Ownership and serialized files

- Coordinator: portfolio, OpenSpec, integration, `classify.rs` and ingress validation, final combined gates.
- Association worker: `ripdpi-tunnel-core/src/io_loop/udp_assoc/**` and DNS lease housekeeping. F2 and F3 remain separate commits.
- Datagram worker: `ripdpi-tunnel-core/src/session/udp.rs`, its tests, and `io_loop/packet/udp_response.rs` plus fragmentation helpers.
- Provider worker: Xray/local-hop configuration and provider regression tests. Coordinate `worker/spawn.rs` changes with the association owner; do not write it concurrently.
- Cargo.lock, dependency versions, proto, locale sets, generated baselines and golden files have no planned changes. Coordinator owns any required contract snapshot regeneration.
- Each writer uses a separate worktree. Review agents are read-only. No worker pushes or integrates main.
