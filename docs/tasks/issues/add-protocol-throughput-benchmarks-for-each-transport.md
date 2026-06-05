---
title: Add Criterion throughput benchmarks for each transport
type: task
status: todo
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-05
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

## Work log

- 2026-06-05: `ripdpi-bench` exists with `relay_throughput.rs` but benchmarks only generic tcp-echo (1MiB/64KiB/1KiB), not per-transport (VLESS/xHTTP/MASQUE/Hysteria2/TUIC/ShadowTLS/WS-tunnel). No `baselines/` dir under `ripdpi-bench/`. CI has `rust-criterion-bench` job with `check-criterion-regressions.py` but uses `--warn-only` and `rust-bench-baseline.json` lacks per-transport entries. All three acceptance criteria remain unmet.
