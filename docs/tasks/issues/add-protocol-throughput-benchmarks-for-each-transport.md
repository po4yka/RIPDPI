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

- [~] One Criterion benchmark per transport that drives a loopback pair through a representative payload size (1 MiB). **Partial**: `protocol_throughput.rs` covers VLESS+Reality and VLESS-over-xHTTP-over-Reality (driven against `VlessRealityLoopback` / `XhttpRealityLoopback`). Deferred: ShadowTLS (blocked on [[investigate-shadowtls-stream-concurrent-throughput]] — measured ~0.5 MiB/s under concurrent r/w), Hysteria2/TUIC (need a QUIC proxy-server loopback, not the generic `QuicLoopback` echo), MASQUE/WS-tunnel (drive the existing fixtures through their clients).
- [ ] Baselines committed under `native/rust/crates/ripdpi-bench/baselines/`. **Deliberately not done from a dev box**: Criterion numbers are host-dependent, so a dev-machine baseline would gate CI on noise. The baseline must be captured on the CI reference runner; capture procedure documented in the crate README.
- [ ] `regression-detector` agent is wired into a nightly CI lane. **Pending the reference-runner baseline above.**

## Definition of done

- A deliberate 25% slowdown in any one transport fails the regression-detector lane.

## Links

- [[Epic - Control-plane hardening]]

## Work log

- 2026-06-05: `ripdpi-bench` exists with `relay_throughput.rs` but benchmarks only generic tcp-echo (1MiB/64KiB/1KiB), not per-transport (VLESS/xHTTP/MASQUE/Hysteria2/TUIC/ShadowTLS/WS-tunnel). No `baselines/` dir under `ripdpi-bench/`. CI has `rust-criterion-bench` job with `check-criterion-regressions.py` but uses `--warn-only` and `rust-bench-baseline.json` lacks per-transport entries. All three acceptance criteria remain unmet.
- 2026-06-05: added `protocol_throughput.rs` with per-transport 1 MiB full-duplex throughput benches for VLESS+Reality and VLESS-over-xHTTP-over-Reality, each driving the real client against its loopback fixture with a concurrent write/read round-trip (handshake established once, outside the timed loop). Documented the baseline-capture-on-CI-reference-runner requirement (no dev-box baselines committed). Surfaced a ShadowTLS throughput collapse (~0.5 MiB/s) under concurrent split read+write → filed [[investigate-shadowtls-stream-concurrent-throughput]] and deferred the ShadowTLS bench case until that lands. Hysteria2/TUIC still need a QUIC proxy-server loopback; MASQUE/WS-tunnel need their clients wired to the existing fixtures.
