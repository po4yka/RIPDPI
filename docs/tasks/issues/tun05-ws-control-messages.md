---
id: RST-1794478552541413
title: Handle WebSocket control messages inside the carrier
kind: bug
status: review
area: rust-native
priority: medium
owner: Native lifecycle agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-ws-control-messages
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Handle valid Ping/Pong inside the carrier and deliver only datagrams.

## Acceptance criteria

Affected crate tests pass. Independent review has no unresolved findings. Device and external compatibility are not claimed.

## Ownership

Native lifecycle agent owns native/rust/crates/ripdpi-wireguard-ws/src/receive.rs and tests/loopback_roundtrip.rs. Other agents own Kotlin readiness, URL validation, and service recovery in isolated worktrees. No shared lockfile, version, baseline, golden, or locale edits.
