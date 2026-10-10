---
id: DGN-1791536817237300
title: Measure active packet size and path MTU evidence
kind: feature
status: review
area: diagnostics
priority: high
owner: pmtu-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: active-pmtu-diagnostics
created: 2026-10-09
updated: 2026-10-09
status_detail: Implementation and local gates passed; remote CI, APK producer artifacts and device acceptance remain separate.
---

## Goal

Measure acknowledged UDP packet sizes with active QUIC DPLPMTUD on separate IPv4 and IPv6 paths.

## Acceptance criteria

Real QUIC clear-path and size-cliff tests, bounded cancellation, typed contracts, privacy/scope, current/history UI and locale gates pass. Record CI, device and artifact acceptance separately.

## Ownership

- Native writer: all Rust diagnostic sources/tests/manifests; no Cargo.lock, public API snapshots, schema versions or golden writes.
- UI writer: app mappings/cards/tests and all ten locale resource sets only.
- Integration writer: Kotlin contracts, catalog sources/tests, generated asset, request/full-run wiring, scope/privacy, task/spec/docs, Cargo.lock, API snapshots and translation manifest.
- Golden specialist: only the two authorized shared catalog/taxonomy fixtures, in an isolated worktree.
- Each writer uses a separate worktree. Gradle runs are serialized. No writer commits; integration owns the final commit.
