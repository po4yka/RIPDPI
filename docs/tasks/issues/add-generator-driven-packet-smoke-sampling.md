---
title: Add generator-driven packet-smoke sampling
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-25
updated: 2026-05-25
---

- [ ] #task Add generator-driven packet-smoke sampling #repo/RIPDPI #area/testing #status/backlog 🔼

## Summary

Add a packet-smoke generator that samples the documented 7-dimensional desync parameter space, records the seed and axis values, and reuses the existing pcap-shape oracle.

## Motivation

Hand-authored packet-smoke scenarios preserve known recipes, but they do not cover regressions in less-traveled combinations of split offsets, TLS record handling, QUIC fakes, fake TTL, UDP bursts, and OOB placement.

## Scope

- In scope: generator manifest format, PR sample budget, nightly sample budget, deterministic seed recording, and packet-smoke registry integration.
- Out of scope: replacing named scenarios or widening the threat model beyond the axes in the design spike.

## Acceptance criteria

- [ ] PR packet smoke runs all named scenarios plus a bounded generated sample set.
- [ ] Nightly packet smoke runs a larger generated set and records enough metadata to reproduce any failure.
- [ ] Generated fixtures include `generator_seed`, `generator_axis_values`, and `generator_origin`.
- [ ] Unit tests prove the same seed produces stable cells and that scenario filters still exist in the registry.

## Links

- [Design spike: generator-driven packet-smoke](../../architecture/spike-generator-packet-smoke.md)
- [Parent spike](spike-adversarial-network-harness-and-realprovider-matrix.md)
