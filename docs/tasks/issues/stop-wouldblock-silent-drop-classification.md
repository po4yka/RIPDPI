---
id: DGN-1786471243477218
title: Stop classifying WouldBlock as silent drop
kind: bug
status: done
area: diagnostics
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-11
updated: 2026-08-11
spec_reason: regression-tested-single-module
related_tasks: []
closed_at: "2026-08-11T18:15:17Z"
closed_reason: WouldBlock is local transient unreadiness, not evidence of a network silent drop
evidence_summary: cargo test -p ripdpi-failure-classifier --locked; cargo test -p ripdpi-proxy-runtime-adapter --locked; cargo test -p ripdpi-proxy-runtime --locked --lib; cargo clippy -p ripdpi-failure-classifier --locked --all-targets -- -D warnings; ./gradlew staticAnalysis
---

## Goal

Treat `io::ErrorKind::WouldBlock` as local transient I/O unreadiness instead of evidence that the network silently dropped traffic.

## Acceptance criteria

- `classify_transport_error` keeps genuine `TimedOut` failures classified as `SilentDrop`.
- `WouldBlock` is classified as `Unknown` with `SurfaceOnly`, so it cannot trigger silent-drop strategy selection or group retry.
- The classifier preserves `kind=WouldBlock` evidence for diagnostics.
- The focused crate test and repository static analysis pass.
