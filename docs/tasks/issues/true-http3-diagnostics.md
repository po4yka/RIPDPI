---
id: DGN-1791532838740620
title: Validate real HTTP3 request and response diagnostics
kind: feature
status: review
area: diagnostics
priority: high
owner: http3-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: true-http3-diagnostics
created: 2026-10-09
updated: 2026-10-09
status_detail: Implementation and combined local gates passed; remote CI, APK producer artifact and device acceptance remain separate.
---

## Goal

Provide real HTTP/3 measurements, separate from QUIC Initial replies, in manual and full diagnostic scans.

## Acceptance criteria

Real local QUIC/H3 server tests, certificate and ALPN rejection, body limits, cancellation, scope invalidation, UI/history/export and locale checks pass. Record device, artifact and remote CI gaps separately.

## Ownership

- Native writer: all Rust diagnostic source and tests, including crate manifests. No Cargo.lock, API snapshot, schema-version or fixture writes.
- UI writer: app mapping/cards/tests and all ten locale resource sets. No core or native changes.
- Catalog writer: build-logic/convention catalog Kotlin sources and tests only; isolated worktree. No generated assets or fixtures.
- Integration writer: Kotlin shared models, request/full-analysis wiring, privacy and scope, tests, task/spec/docs; sole Cargo.lock and API snapshot writer.
- Golden specialist: only generated catalog asset and the two authorized shared catalog/taxonomy fixtures in an isolated worktree.
- Gradle runs are serialized. Rust public contracts are owned by the native writer; Kotlin models by integration. Integration applies reviewed patches.
