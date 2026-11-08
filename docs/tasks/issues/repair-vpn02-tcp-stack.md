---
id: VPN-1794140727483999
title: Repair VPN-02 TCP stack regressions
kind: bug
status: doing
area: vpn
priority: high
owner: VPN TCP repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-vpn02-tcp-stack
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair the five confirmed VPN-02 audit defects without changing the Android non-root contract.

## Acceptance criteria

- Both half-close orders deliver all bytes and retain TIME-WAIT for repeated FIN.
- A failed relay after upstream EOF releases its live TCP session within the idle and drain budgets.
- New tunnel interfaces use fresh OS entropy.
- Configured TCP buffers bound native RX/TX capacity.
- IPv6 without MSS uses 1220; IPv4 keeps 536.
- Each repair has a separate reviewed commit. Local gates pass before integration into main and push.

## Parallel ownership

- Lifecycle worker: tunnel-core bridge, sessions, TCP task completion, private admission/session.rs and duplex/launch.rs completion notifications, io_loop/tcp_accept/eviction.rs, and lifecycle regression tests. After the MSS commit, this lane also owns the minimal smoltcp close-outcome/timer source patch and its provenance. F1 and F2 are serialized in this lane.
- Entropy worker: tunnel-core tunnel_api.rs, its manifest, and fresh-interface tests. This lane owns Cargo.lock until F3 is reviewed and committed.
- Upstream worker: vendor/smoltcp and its provenance, workspace patch entry, tests/tcp_mss.rs, scripts/ci/run-rust-workspace-tests.sh family suites, and upstream MSS regression. Cargo.lock ownership transfers only after the entropy commit.
- Coordinator: TCP buffer admission/runtime callers and tests, planning artifacts, generated board, combined validation, integration and push.
- Read-only reviewers do not change files. Every writer uses a separate worktree; no worker updates planning files or main.
