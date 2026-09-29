---
id: RST-1790681076965083
title: Connect existing UDP protocol classification
kind: feature
status: doing
area: rust-native
priority: high
owner: Native strategy
parent: null
blocked_by: []
spec_mode: required
openspec_change: udp-protocol-classification
created: 2026-09-29
updated: 2026-09-29
---

## Goal

Use existing UDP detection for TUN protocol filters and strategy dissection. Protocol-scoped rules must not change unrelated datagrams.

## Acceptance criteria

- QUIC, DTLS, STUN, DHT, and WireGuard rules match only their detected UDP payloads on IPv4 and IPv6.
- Lua receives typed UDP context; QUIC host/version/markers and empty/Any filters remain valid.
- Short, unknown, neighboring-protocol, wrong-port, and wrong-host packets pass without scoped injection.
- Existing non-root ownership and TCP behavior pass their regression gates.
- Affected Rust tests, lint, architecture gates, Android evidence, independent review, main integration, and remote SHA are observed.

## Ownership

Primary owns native/rust/crates/ripdpi-tunnel-intercept, native/rust/Cargo.lock, and task/specification artifacts in feat/udp-classification. Other agents only inspect, run tests, or review. Cargo.lock and generated task board have one writer.
