---
id: ENG-1794755247754263
title: Fix AUX-03 geo database validation and matching
kind: bug
status: doing
area: engine
priority: high
owner: aux03-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: aux03-geo-integrity
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Fix confirmed AUX-03 findings F1-F6. Preserve working geo databases when validation fails and match the supported upstream rule semantics.

## Acceptance criteria

- Cover each confirmed finding with a behavior regression test.
- Pass owning Kotlin/Rust tests, JNI checks, and ci-gates before integration.
- Commit each finding separately and publish validated commits to main.

## Ownership

- aux03-native: ripdpi-geo sources/tests; F1, F2, F4, F5. This is one serialized writer lane.
- aux03-assets: Kotlin geo asset delivery/metadata and existing native metadata-admission adapter if needed; F3, F6. Do not edit ripdpi-geo.
- aux03-integration: portfolio/OpenSpec, API snapshots, combined tests, review, and main integration.
- Cargo.lock, manifests, protobuf, locale resources, baselines, and golden files stay in the integration lane; no new production dependency is planned.
- Each worker uses a separate worktree. Review agents are read-only.
