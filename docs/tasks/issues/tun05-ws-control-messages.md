---
id: RST-1794478552541413
title: Handle WebSocket control messages inside the carrier
kind: bug
status: done
area: rust-native
priority: medium
owner: Native lifecycle agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-ws-control-messages
created: 2026-11-12
updated: 2026-11-12
closed_at: "2026-11-12T11:09:31Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Source 38b8f4bac6 matches the verified application tree: 2288 Kotlin and 234 Rust tests, staticAnalysis, ci-gates and independent reviews passed. Device and external acceptance are not claimed."
---

## Goal

Handle valid Ping/Pong inside the carrier and deliver only datagrams.

## Acceptance criteria

Affected crate tests pass. Independent review has no unresolved findings. Device and external compatibility are not claimed.

## Ownership

Native lifecycle agent owns native/rust/crates/ripdpi-wireguard-ws/src/receive.rs and tests/loopback_roundtrip.rs. Other agents own Kotlin readiness, URL validation, and service recovery in isolated worktrees. No shared lockfile, version, baseline, golden, or locale edits.
