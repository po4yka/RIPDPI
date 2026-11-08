---
id: PRX-1794124642980782
title: Reuse compatible TCP connections after payload routing
kind: bug
status: review
area: proxy
priority: high
owner: Proxy runtime maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: connect-route-reuse
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Keep an established TCP connection when payload routing selects a group with
the same connection policy and destination. Apply the selected payload strategy.

## Acceptance criteria

- A regression fails before the fix and passes with the fix.
- Delayed HTTP CONNECT completes verified TLS and an exact application exchange
  with an origin that accepts one TCP connection only.
- Incompatible connection policies and blocked destinations retain their gates.
- Relevant runtime tests, Clippy, formatting, metadata and source gates pass.

## Ownership

- Primary agent: planning, native documentation, verification and task lifecycle
  in the connect-route-reuse worktree.
- Implementation worker: runtime sources and regression tests in a separate
  worktree; no planning or shared baseline/dependency edits.
- Review agents: read-only contract, code and cancel-safety review.
- No dependency, schema, baseline, fixture golden or locale changes are planned.
