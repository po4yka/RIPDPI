---
id: AND-1795015211660121
title: Preload a preset in the full app and remove the simple flavor
kind: feature
status: done
area: android
priority: medium
owner: Implementer
parent: null
blocked_by: []
spec_mode: required
openspec_change: preload-preset-remove-simple-flavor
created: 2026-11-18
updated: 2026-11-19
closed_at: "2026-11-19T11:48:15Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Local: :app:testGithubDebugUnitTest (3764 tests, 0 failures) and :app:detekt pass on main fd00bf493. Remote CI: run 44369551319 on main 885a098d1 succeeded, including unit coverage, Roborazzi, three release variants, and instrumented tests on API 27-37. Device: fresh installs with and without a preset on the API 35 pool emulator."
---

## Goal

Every distribution channel builds one application. A build that packages a preset (a FULL backup document) restores its servers, routing rules, and settings once on the first launch of a fresh install. The `simple` experience flavor, its application ID, and its failover stack are removed.

## Acceptance criteria

- A fresh install of a build with a preset shows the preset's groups and settings on the first screen, with no import step and no visible restart.
- A preset is never re-applied and never overwrites an install that completed onboarding.
- A build without a preset behaves as the current full experience and needs no asset, secret, or fixture.
- The release identity manifest lists one release variant per channel and no `.simple` application ID.
- `openspec/changes/preload-preset-remove-simple-flavor/verification.md` maps every requirement to observed evidence.
