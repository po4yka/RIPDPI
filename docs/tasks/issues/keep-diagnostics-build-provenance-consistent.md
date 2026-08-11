---
id: DGN-1786485162657326
title: Keep diagnostics build provenance consistent
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
created: 2026-08-12
updated: 2026-08-12
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: Installed package version and product flavor now drive developer analytics provenance; focused regression, archive allow-list, lint, and staticAnalysis pass.
closed_at: "2026-08-11T22:13:36Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: RED observed expected (0.1.4-simple, githubFull) but actual (0.1.4-debug, debug); focused app regression, DeveloperAnalyticsAllowListTest, app/core diagnostics lint, and staticAnalysis passed.
---

## Goal

All build-provenance sections in a diagnostics archive identify the installed
application artifact with the same version name.

## Acceptance criteria

- `developer-analytics.json` derives its application version from the installed
  package, matching `archive-provenance.json` for flavored builds.
- Developer analytics reports the actual product flavor rather than repeating
  the build type in `buildFlavor`.
- The regression test covers a package version that differs from the generated
  `BuildConfig.VERSION_NAME` value and distinguishes flavor from build type.
- Focused app tests, archive tests, lint, and repository static analysis pass.
