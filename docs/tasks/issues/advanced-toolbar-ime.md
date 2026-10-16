---
id: UIX-1792168730775866
title: Keep the Advanced toolbar fixed while the keyboard is open
kind: bug
status: doing
area: ui
priority: high
owner: Advanced IME team
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-16
updated: 2026-10-16
spec_reason: regression-tested-single-module
---

## Goal

Keep the Advanced title and Back target below the status bar while IME is
visible. Keep the focused field visible and retain the existing save and Back
behavior. This is a single-module layout regression; no window policy, storage,
service or navigation contract is changed.

## Acceptance criteria

- Capture the pan on a fresh main542 APK before changing production code.
- A measured regression fails before the change and passes after it.
- On API35+ and API30, the toolbar and its 48dp Back target stay below the
  actual status bar at normal/maximum font in gesture/three-button navigation.
- The focused field stays above the actual IME. The first Back hides IME;
  the toolbar Back then returns to the expected screen. Existing edit/save works.
- Hidden-IME geometry and verified screenshot fixtures stay unchanged.
- Focused tests, theme checks, static analysis, app/service lint and the affected
  screenshot checks pass. Record exact APK source and hashes for device work.
- Review the final diff, integrate with ff-only into main and push. Record CI.

## Ownership

- Integration owner: AdvancedSettingsContent.kt, integration of regression tests,
  task records, evidence, combined checks and Git integration. Owns all production
  edits. Keep the change on Advanced unless runtime evidence requires more.
- Test worker, isolated RIPDPI-advanced-ime-test: only a new
  AdvancedSettingsImeInsetsTest.kt in app/src/test/kotlin/.../screens/settings.
  No production, locale, golden, task, build or shared scaffold edits.
- Device specialist: temporary harness and capture files outside tracked source;
  exclusive command-scoped emulator leases and restoration. No compilation.
- Independent reviewer: read-only final source and test review.

## Design and limits

RDS references: docs/design/rds/preview/components-settings-row.html and
vpn-quick-vs-advanced.html. Spec HTML stays read-only. Keep the existing RDS
scaffold and tokens; reserve IME space without extra status-bar padding.
No SOFT_INPUT_ADJUST flags, second enableEdgeToEdge call, new dependency,
locale key, golden blessing, service action or network probe is planned.
