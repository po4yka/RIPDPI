---
id: PRX-1794114937480022
title: Enforce SOCKS5 token authentication contracts
kind: bug
status: review
area: proxy
priority: high
owner: Native protocol team
parent: null
blocked_by: []
spec_mode: required
openspec_change: loc03-token-auth-contracts
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Fix the four confirmed LOC-03 authentication defects. Preserve valid token clients and credential privacy.

## Acceptance criteria

- Reject SOCKS5 listener tokens outside 1..=255 bytes before bind.
- Reject malformed RFC 1929 response versions before CONNECT or ASSOCIATE.
- Reject empty or oversized TUN credentials at configuration and handshake boundaries.
- Preserve correlation for canonical IPv4 and IPv6 loopback peers, including mapped IPv4.
- Run regression tests, independent review, affected Rust tests and source CI gates.
- Integrate one fix commit per finding into main and push origin/main as requested.

## Parallel ownership

- Server worker: F1; ripdpi-proxy-runtime runtime/state.rs, runtime.rs tests, dedicated auth validation tests. Worktree /private/tmp/ripdpi-loc03-server.
- Client worker: F2 then F3 as separate commits; ripdpi-tunnel-core session/socks5/auth.rs and tests, ripdpi-tunnel-config validation and tests. Worktree /private/tmp/ripdpi-loc03-client.
- Integrator: F4; protocol_io/readers.rs and correlation tests; all task/OpenSpec artifacts and combined gates. Worktree /private/tmp/ripdpi-loc03-auth.
- Review agents: read-only. No parallel writer shares a worktree.
- Serialized shared lanes: integrator alone owns task files and normative specs. No lockfile, dependency, API snapshot, locale, golden, baseline, schema or registry changes are planned.
