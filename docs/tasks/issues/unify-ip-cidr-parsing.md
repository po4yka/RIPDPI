---
id: RTE-1793945596239402
title: Unify strict IP and CIDR parsing with maintained libraries
kind: bug
status: blocked
area: routing
priority: high
owner: ip-cidr
parent: null
blocked_by: []
spec_mode: required
openspec_change: unify-ip-cidr-parsing
created: 2026-11-06
updated: 2026-11-06
status_detail: "Local validation and main push passed. Full hosted acceptance is unverified: this SHA's CI was cancelled by a later push; upstream rust-lint has an HTTP/1 shared-state violation outside the IP/CIDR change."
---

## Goal

Use library-backed strict IP and CIDR parsing in Kotlin and Rust. Reject malformed values without DNS and keep valid routing and interface behavior.

## Acceptance criteria

- Regression tests reject malformed IPv6, legacy IPv4, invalid prefixes, and cross-family subnet matches.
- Host CIDRs retain host bits; network CIDRs and canonical routing bytes remain correct.
- Targeted Kotlin and Rust tests, source gates, and independent review pass before integration.
- Commit, integrate into main, push, and report hosted CI separately from local validation.

## Ownership

- Root: Kotlin shared parser in core:data:model, Gradle manifests/version catalog, task/OpenSpec records, combined validation and integration.
- Kotlin caller worker: app/core callers and tests outside core:data:model, in an isolated worktree.
- Rust worker: native/rust source and manifests, including the serialized Cargo.toml/Cargo.lock lane, in an isolated worktree.
