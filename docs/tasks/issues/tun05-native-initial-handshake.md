---
id: RST-1794477240707368
title: Bound AmneziaWG initial handshake cancellation
kind: bug
status: done
area: rust-native
priority: high
owner: Native lifecycle agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-native-initial-handshake
created: 2026-11-12
updated: 2026-11-12
closed_at: "2026-11-12T11:09:27Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Source 38b8f4bac6 matches the verified application tree: 2288 Kotlin and 234 Rust tests, staticAnalysis, ci-gates and independent reviews passed. Device and external acceptance are not claimed."
---

## Goal

Stop and the peer deadline cover the first WS write and authenticated readiness.

## Acceptance criteria

Affected crate tests pass. Independent review has no unresolved findings. Device and external compatibility are not claimed.

## Ownership

Native lifecycle agent owns native/rust/crates/ripdpi-warp-core/src/amneziawg_runtime.rs; test-only wireguard/carrier.rs fixture. Other agents own Kotlin readiness, URL validation, and service recovery in isolated worktrees. No shared lockfile, version, baseline, golden, or locale edits.
