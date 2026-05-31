---
title: Add cross-stack chain tests (VLESS over xHTTP over Reality)
type: task
status: doing
area: testing
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-31
---

## Summary

Real deployments stack protocols (VLESS-over-xHTTP-over-Reality, or VLESS-over-Reality with mux). Add loopback tests that exercise representative combinatorics so per-crate changes do not break stacked behavior.

## Context

Per-crate tests do not catch interaction bugs between transport layers: an xHTTP framing change that's correct in isolation can break the VLESS handshake that runs over it.

## Acceptance criteria

- [ ] At least two cross-stack tests: - VLESS-over-Reality with mux, two concurrent streams. - VLESS-over-xHTTP-over-Reality, single stream.
- [ ] Each test asserts payload integrity in both directions.
- [ ] Tests run in the standard CI lane.

## Definition of done

- A correctness regression in any one layer breaks the cross-stack test even when per-crate tests pass.

## Links

- [[audit-vless-chained-connect-over-relay-end-to-end-tests]]
- [[add-vless-mux-conformance-tests-against-xray-core]]
