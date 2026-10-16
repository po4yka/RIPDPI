---
id: UIX-1792123188281598
title: Restore connection feedback and accessible UI controls
kind: bug
status: review
area: ui
priority: high
owner: UI repair team
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-16
updated: 2026-10-16
spec_reason: regression-tested-single-module
status_detail: Final aafb448a5 source passed 170 JVM tests, staticAnalysis, app/service lint, Full app/test APK builds and 5 API 37 UI checks. Architecture and locked metadata passed. CI remains a separate failed coverage check.
---

## Goal

Restore existing app UI contracts found in the design audit: show rejected starts,
keep pending connection actions visible, expose setting values to accessibility,
return to the previous onboarding step, and keep unknown progress indeterminate
when motion is disabled. Scope is limited to app-module regression fixes.

## Acceptance criteria

- Connection shows a rejected start reason and a working VPN-consent recovery action.
- Pending starts and stops expose progress and reject duplicate submissions.
- VPN and local-bypass setting rows expose their title, safe value, and one action.
- Onboarding has visible back navigation; system Back returns to the previous step.
- Static unknown progress keeps indeterminate semantics and a visible work indicator.
- Targeted regressions, static analysis, locale lint, and emulator checks have observed results.

## Parallel ownership

- Connection worker owns Config ViewModel/state, config screens, their setting-row
  semantics, and focused connection tests. It does not edit locale resources.
- Onboarding worker owns onboarding UI and its focused tests. It does not edit
  locale resources or shared test-tag files.
- Integration owner owns progress indicators, shared resources/test tags, task
  records, combined-tree checks, emulator evidence, and final integration commits.
- All writers use separate worktrees. Locale resources, task records, and shared
  test doubles are serialized through the integration owner. No golden fixtures,
  dependency manifests, build gates, or service-engine contracts change.

## Verification plan

Run each reproducing regression before its fix, then repeat the affected tests.
Run app static analysis, app/service locale lint, and assemble the Full debug APK.
Use a leased API 37 emulator for rejected start/recovery, pending UI, accessible
settings, onboarding Back, and disabled-motion progress. Check relevant light,
dark, and large-font states without blessing screenshot fixtures.

## Observed verification

- JVM regressions: 170 tests passed with no failures, errors, or skipped tests.
- API 37 UI checks: 5 instrumented tests passed on the Full arm64 APK.
- Real app: a missing-consent rejection revealed its reason and recovery without
  another scroll; recovery opened the Android VPN dialog and started VPN.
- Dark onboarding with font scale 2.0: visible Back and system Back returned to
  the intro. Font scale was restored to 1.0.
- Independent review found no remaining defects after the superseded-stop fix
  and the error-visibility fix.
- Final verification repeated on main source aafb448a5, including the final
  initial-snapshot memoization and preset-list extraction: 170 JVM tests passed
  with no failures, errors, or skipped tests. Static analysis, app Full and Simple
  lint, service lint, and Full app and test APK builds passed in one Gradle run.
- The rebuilt Full arm64 APK passed all 5 focused UI tests on a leased API 37
  emulator. No test was retried or skipped. All 5 fresh screenshots were inspected.
  App APK SHA-256: 4688841ad813493eb583b661dc9139255eb28438df383434983207c253632acb.
- The repeat uses component instrumentation with injected states and callbacks.
  It verifies UI behavior; it does not repeat the real VPN-service smoke above.
- Architecture health and locked Cargo metadata checks passed on the final tree.

## Design references and limits

The onboarding-tour-cards reference gains Back after the intro. The progress-bar
reference gains a centered static marker when motion is disabled. These changes
restore navigation and progress meaning. Existing RDS surfaces and strings are
used. No locale resources, golden fixtures, or quality baselines change.

The extra Simple APK build needs the absent local embedded-relay-bundle.json.
Actual TalkBack speech, physical devices, and release frame performance are not
covered by this local repair. CI is separate: the published aafb448a5 run reports
a kotlin-coverage failure. Its logs could not be retrieved; no cause is claimed.
Local screenshots and the layered verification report are in
build/reports/ui-first-stage/. Fresh exact-source device evidence is in
/tmp/ripdpi-ui-exact-main-recheck/20261016T055349Z.
