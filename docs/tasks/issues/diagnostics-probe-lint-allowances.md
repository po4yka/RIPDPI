---
id: DGN-1795610825637073
title: Remove lint allowances in the diagnostics probe crates
kind: chore
status: backlog
area: diagnostics
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`native/rust/crates/ripdpi-diagnostics-dns/src/cdn_ech.rs` has `#![allow(dead_code)]` over helpers that only tests use. `native/rust/crates/ripdpi-diagnostics-transport/src/transport/udp/quic_version_matrix.rs#exchange` has `#[allow(clippy::too_many_arguments)]`.

## Acceptance criteria

The dead helpers are deleted or gated with `cfg(test)`, the arguments of `exchange` are grouped or the allowance is justified in an ADR, and clippy stays clean with `-D warnings`.
