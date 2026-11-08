---
id: PRX-1794127562446757
title: Restrict exit session accounting to the direct path
kind: bug
status: done
area: proxy
priority: medium
owner: session-accounting
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-08
updated: 2026-11-08
spec_reason: regression-tested-single-module
closed_at: "2026-11-08T09:08:01Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: LOC-11 P2-02 fixed at cbedaf8a833707c59ef3b2f1cd5fe338cee8589a. Real SOCKS regression failed before direct-only admission; six wiring tests and combined proxy library 351/351 passed. Direct policy bypass keeps its permit. Independent review, clippy, just ci-gates, and locked metadata passed.
---

## Goal

Apply the proxy direct-path session budget only to a direct physical connection. Upstream SOCKS destination addresses must not consume permits that claim to count physical peers. The relay owner retains its separate carrier budget.

## Acceptance criteria

- Multiple targets through one real loopback SOCKS upstream do not consume direct-destination permits.
- Direct connections still acquire and release their permits, including destination-policy direct bypass with an upstream configured.
- Authentication, failure cleanup, and advisory all-candidates-at-cap fallback stay intact.
- Routing tests, affected lint, and source CI gates pass before integration.

## Ownership

- The session-accounting writer owns the routing plan and its tests in the separate `fix/loc11-direct-budget` worktree. Integration follows the reconnect fix; the writers do not share working files.
- The integration owner owns the task records and generated board. No parallel writer changes this worktree or serialized dependency/contract files.

## Evidence

LOC-11 P2-02 was reproduced at base `9673571bba07581f2b99cd02014645af96bd8a88`: nine physical connections to one SOCKS upstream each acquired a slot under a different destination IP. The normative policy assigns this limiter to the proxy direct path; this fix does not add a new upstream or global hard cap.

The new real-socket regression failed before the change because upstream targets received direct permits. After the change, `cargo test -p ripdpi-proxy-runtime --lib --locked runtime::routing::connect::plan::exit_ip_cap_wiring_tests` passed all six tests. The tests cover nine upstream sessions, direct destination-policy bypass, direct permit release, alternate candidates, advisory fallback, and connect failure. Full combined module and source gates are required before integration.
