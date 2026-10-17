---
id: UIX-1792226888578993
title: Measure and improve UI responsiveness
kind: feature
status: done
area: ui
priority: high
owner: UI performance integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: ui-stage-four
created: 2026-10-17
updated: 2026-10-17
closed_at: "2026-10-17T17:27:23Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Main d8c6a7264dad7ece1606e966f98453b6f0e8b223 passed CI 39050341384 including ci-required, all API 27/33/35/36/37 instrumented checks and release variants; local v19 passed 360 host and 16 screenshot tests plus all lint gates; API 37 paired performance comparison retains 36 traces and mixed metrics in docs/audits/2026-10-17-ui-responsiveness.md.
---

## Goal

Keep logs, navigation and catalog input responsive under repeated telemetry and large local catalogs. Measure the original and revised implementations on one leased emulator.

## Acceptance criteria

- Preserve ordered, deduplicated logs, filters, clear and active-session state.
- Avoid event mapping for counter-only telemetry and progress-only updates; run heavy log projection off Main.
- Avoid catalog filter/group/sort work for unrelated recomposition; preserve query, favorites, recent and applied ordering.
- Isolate shell permission state from telemetry changes without stale permissions.
- Compare immutable before/after APKs with the same deterministic workloads and capture frame/timing artifacts. Emulator results do not prove physical-device performance.
- Pass focused behavior, static analysis, architecture and locked metadata gates; independent review; integrate and push main as authorized.

## Ownership

- Logs writer: LogsViewModel.kt, LogsViewModelDependencies.kt, log projection helpers, related unit tests. Dedicated perf/ui-stage-four-logs worktree.
- Catalog writer: ProfileUtilityScreen.kt, catalog projection helpers and related unit tests. Dedicated perf/ui-stage-four-catalog worktree.
- Measurement writer: benchmark-only app driver, baselineprofile tests and benchmark source manifest. Dedicated perf/ui-stage-four-measure worktree. No production source ownership.
- Integration owner: MainViewModel permission projection and RipDpiNavHost, all build scripts, task/OpenSpec files, report and serialized shared files. The version catalog has one benchmark-only Perfetto binary pin, which matches the existing resolved tracing version. No locale, golden, schema or production dependency updates are planned.
- Root activity regression writer: MainActivityContentTest.kt in the isolated logs worktree. The integration owner owns MainActivityContent.kt and the activity runtime projection in MainUiModels.kt and MainViewModel.kt.
