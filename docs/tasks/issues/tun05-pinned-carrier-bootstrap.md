---
id: RST-1794477836924583
title: Pin WebSocket carrier bootstrap addresses
kind: bug
status: review
area: rust-native
priority: high
owner: Native lifecycle agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun05-pinned-carrier-bootstrap
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Use explicit numeric underlay addresses while retaining the WSS URL identity.

## Acceptance criteria

Affected crate tests pass. Independent review has no unresolved findings. Device and external compatibility are not claimed.

## Ownership

Native lifecycle agent owns native/rust/crates/ripdpi-wireguard-ws/src/{connect,endpoint,lib}.rs; warp-core runtime DTO and carrier connector. Other agents own Kotlin readiness, URL validation, and service recovery in isolated worktrees. No shared lockfile, version, baseline, golden, or locale edits.
