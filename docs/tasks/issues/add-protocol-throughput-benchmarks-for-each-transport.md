---
title: Add Criterion throughput benchmarks for each transport
type: task
status: doing
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-31
---

## Summary

Wire one Criterion benchmark per transport (VLESS, xHTTP, MASQUE, Hysteria 2, TUIC, ShadowTLS, WS tunnel) into `ripdpi-bench` so the `regression-detector` agent can gate throughput regressions per release.

## Context

`ripdpi-bench` exists in the workspace. The regression-detector agent expects checked-in Criterion baselines for each transport. Today there is no per-protocol throughput signal in CI, so a 30% bandwidth regression in xHTTP could ship unnoticed.

## Acceptance criteria

- [ ] One Criterion benchmark per transport that drives a loopback pair through a representative payload size (e.g. 1 MiB).
- [ ] Baselines committed under `native/rust/crates/ripdpi-bench/baselines/`.
- [ ] `regression-detector` agent is wired into a nightly CI lane.

## Definition of done

- A deliberate 25% slowdown in any one transport fails the regression-detector lane.

## Links

- [[Epic - Control-plane hardening]]
