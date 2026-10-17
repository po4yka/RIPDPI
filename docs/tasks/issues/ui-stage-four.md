---
id: UIX-1792226888578993
title: Measure and improve UI responsiveness
kind: feature
status: doing
area: ui
priority: high
owner: UI performance integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: ui-stage-four
created: 2026-10-17
updated: 2026-10-17
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
- Integration owner: MainViewModel permission projection and RipDpiNavHost, all build scripts, task/OpenSpec files, report and serialized shared files. No locale, golden, schema or dependency updates are planned.
