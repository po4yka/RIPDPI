---
id: PRX-1794127562446757
title: Restrict exit session accounting to the direct path
kind: bug
status: doing
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
---

## Goal

Apply the proxy direct-path session budget only to a direct physical connection. Upstream SOCKS destination addresses must not consume permits that claim to count physical peers. The relay owner retains its separate carrier budget.

## Acceptance criteria

- Multiple targets through one real loopback SOCKS upstream do not consume direct-destination permits.
- Direct connections still acquire and release their permits, including destination-policy direct bypass with an upstream configured.
- Authentication, failure cleanup, and advisory all-candidates-at-cap fallback stay intact.
- Routing tests, affected lint, and source CI gates pass before integration.

## Ownership

- The session-accounting writer changes the proxy routing plan and its owning tests only after the reconnect fix is committed.
- The integration owner owns the task records and generated board. There are no parallel writes to the proxy crate or serialized dependency/contract files.

## Evidence

LOC-11 P2-02 was reproduced at base `9673571bba07581f2b99cd02014645af96bd8a88`: nine physical connections to one SOCKS upstream each acquired a slot under a different destination IP. The normative policy assigns this limiter to the proxy direct path; this fix does not add a new upstream or global hard cap.
