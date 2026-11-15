---
id: ENG-1794755247754263
title: Fix AUX-03 geo database validation and matching
kind: bug
status: review
area: engine
priority: high
owner: aux03-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: aux03-geo-integrity
created: 2026-11-15
updated: 2026-11-15
status_detail: Geo fixes and owning checks are complete. Global lint has one base issue; minified APK and device acceptance are blocked by public Go source access.
---

## Goal

Fix confirmed AUX-03 findings F1-F6. Preserve working geo databases when validation fails and match the supported upstream rule semantics.

## Acceptance criteria

- Cover each confirmed finding with a behavior regression test.
- Pass owning Kotlin/Rust tests, JNI checks, and ci-gates before integration.
- Commit each finding separately and publish validated commits to main.

## Ownership

- aux03-native: ripdpi-geo sources/tests and the narrow proxy-runtime Geo matcher change; F1, F2, F4, F5. This is one serialized writer lane.
- aux03-assets: Kotlin geo delivery/provenance and the additive native admission bridge; F3, F6. Do not edit ripdpi-geo.
- aux03-integration: portfolio/OpenSpec, API snapshots, combined tests, review, and main integration.
- Integration approved and serialized the existing serde workspace dependency and lockfile edge; native applied it. No package version was added or changed. Native verification generated the JNI export inventory for the F3 commit. Other manifests, schemas, locales, baselines and golden files stay in the integration lane.
- Each worker uses a separate worktree. Review agents are read-only.
