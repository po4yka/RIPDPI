---
id: UIX-1792123188281598
title: Restore connection feedback and accessible UI controls
kind: bug
status: doing
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
