---
id: UIX-1792130566603442
title: Simplify connection, diagnostic results and the log stream
kind: feature
status: doing
area: ui
priority: high
owner: UI stage two team
parent: null
blocked_by: []
spec_mode: required
openspec_change: ui-stage-two
created: 2026-10-16
updated: 2026-10-16
---

## Goal

Make the saved connection path clear, keep the current profile compact, show
one diagnostic outcome with its own evidence, and give log entries the main
screen area. This is stage two of the design audit confirmed by the user.

## Acceptance criteria

- The connection summary explains traffic scope and saved path. The main
  connection action uses the saved Mode, regardless of the settings section.
- The profile summary contains its name, current path and a change action.
  Long CLI guidance and preset lists remain available through disclosure.
- Diagnostics shows one outcome with its own source, time, coverage and
  confidence or an explicit unknown state. Stale or partial evidence is clear.
- Technical-detail disclosure does not silently change the primary scan action.
- Logs use one vertical event stream, compact controls and optional filters.
  There is no refresh animation that does not reload data.
- Meaningful regressions, static analysis, locale lint and rebuilt-APK tests
  pass. Inspect light, dark, 360dp and font-scale 2.0 states on an emulator.
- Record local, CI and device evidence separately. A screenshot does not prove
  user comprehension, TalkBack speech or release performance.

## Ownership

- Connection worker: config screen family and focused config tests; app-only
  presentation and callbacks. Preserve saved Mode and relay activation contracts.
- Diagnostics worker: diagnostics overview, its app UI models/mapping and
  focused tests. Preserve result provenance and recommendation review behavior.
- Logs worker: LogsScreen, LogsViewModel, log UI models and focused tests.
- Integration owner: all locale resources, shared test tags, planning and task
  files, device tests and combined checks. These shared files have one writer.
- Each writer uses a separate worktree. No writer edits another lane. No
  protobuf, Rust, service lifecycle or dependency changes are planned.
- Golden specialist: exclusive ownership of the ten approved screenshot PNGs
  below, in a separate worktree. Other fixtures and quality baselines stay fixed.

## Design references

Use vpn-profile-switcher.html, diagnostic-report-summary.html,
components-log-stream.html and components-log-row.html in docs/design/rds/preview.
Keep the preview files read-only. Intentional differences: compact profile
disclosure, one diagnostic verdict and a stream that fills the available height.


## Approved screenshot scope

On 2026-10-16 the user approved these ten Roborazzi PNG updates under
`app/src/test/screenshots` for the reviewed stage-two design:

- `RipDpiScreenCatalogScreenshotTest`: configScreen, configMediumScreen,
  logsScreen and logsEmptyDarkScreen.
- `BusyStateScreenshotTest.logsStreamLive`: light and dark.
- `MaximumFontNavigationShellScreenshotTest.diagnosticsAtPixel7MaximumFont_full`.
- The config capture in ArabicLocaleScreenshotTest, PersianLocaleScreenshotTest
  and SimplifiedChineseLocaleScreenshotTest.

The source is `234e1b7d79ba838c2df188ad24583b5e4e867d01`. The new contract
uses a compact connection summary, one diagnostic outcome and a larger log
stream. The golden specialist must run the narrow owning record task, inspect
all ten outputs and verify them. No architecture or lint baseline may change.
