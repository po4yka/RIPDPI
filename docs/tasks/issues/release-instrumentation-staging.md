---
id: CIC-1788607352810158
title: Allow release instrumentation configuration with debug staging
kind: bug
status: doing
area: ci
priority: high
owner: Audit integration
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-05
updated: 2026-09-05
spec_reason: tooling-only
---

## Goal

Configure release Android instrumentation when debug AndroidTest components do not exist. GitHub release CI 33961193371 reproduced the null debug component after both release APK builds passed.

## Acceptance criteria

- Skip absent debug AndroidTest components in the debug APK staging callback.
- Preserve debug APK collection and the fail-closed bundle inventory check.
- Configure and build GithubFullReleaseAndroidTest with release testBuildType.
- Pass the complete required CI matrix.

## Ownership

Audit integration owns app/build.gradle.kts, this record and the audit report. The build engineer and review agents are read-only. No dependencies, build types or quality gates change.
