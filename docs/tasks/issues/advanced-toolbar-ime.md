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
behavior. This is a single-module layout regression. The Activity declares the
supported resize policy; the settings form scaffold applies Compose IME insets.
Storage, service and navigation contracts stay unchanged.

## Acceptance criteria

- Capture the pan on a fresh main542 APK before changing production code.
- A measured regression fails before the change and passes after it.
- The merged manifest declares resize for MainActivity and its launcher aliases.
  Shared settings forms and the ModeEditor pass real-keyboard smoke tests for
  the Activity-wide policy.
- On API35+ and API30, the toolbar and its 48dp Back target stay below the
  actual status bar at normal/maximum font in gesture/three-button navigation.
- The focused field stays above the actual IME. The first Back hides IME;
  the toolbar Back then returns to the expected screen. Existing edit/save works.
- Hidden-IME geometry and verified screenshot fixtures stay unchanged.
- Focused tests, theme checks, static analysis, app/service lint and the affected
  screenshot checks pass. Record exact APK source and hashes for device work.
- Review the final diff, integrate with ff-only into main and push. Record CI.

## Ownership

- Integration owner: AndroidManifest.xml, AdvancedSettingsContent.kt,
  RipDpiScaffolds.kt, maestro/03-advanced-settings-edit-save.yaml, regression tests,
  task records, evidence, combined checks and Git integration. Owns all production
  edits. Keep the change on Advanced unless runtime evidence requires more.
- Test worker, isolated RIPDPI-advanced-ime-test: only new
  AdvancedSettingsImeInsetsTest.kt in app/src/test/kotlin/.../screens/settings and
  MainActivityImePolicyTest.kt in app/src/test/kotlin/.../activities.
  No production, locale, golden, task, build or shared scaffold edits.
- Device specialist: temporary harness and capture files outside tracked source;
  exclusive command-scoped emulator leases and restoration. No compilation.
- Independent reviewer: read-only final source and test review.

## Design and limits

RDS references: docs/design/rds/preview/components-settings-row.html and
vpn-quick-vs-advanced.html. Spec HTML stays read-only. Keep the existing RDS
scaffold and tokens; reserve IME space without extra status-bar padding. Apply
the IME padding in the shared settings scaffold so the Activity policy does not
leave other settings forms without keyboard avoidance.
Use the declarative manifest adjustResize attribute required by Android's Compose
setup. The local skill bans imperative WindowManager SOFT_INPUT_ADJUST flags;
the official Android skill explicitly distinguishes those deprecated constants
from the supported manifest attribute. No runtime flag, second enableEdgeToEdge
call, new dependency, locale key, golden blessing, service action or network
probe is planned. The first Compose-only candidate failed real device acceptance
and must not be reported as a completed fix.
