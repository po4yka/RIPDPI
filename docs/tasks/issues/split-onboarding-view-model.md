---
title: Extract OnboardingPermissionCoordinator from OnboardingViewModel
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Extract OnboardingPermissionCoordinator from OnboardingViewModel #repo/RIPDPI #area/ui #status/backlog ⏫

## Objective

Separate permission resolution (VPN consent + notification permission) from `OnboardingViewModel`'s page navigation and validation lifecycle into a dedicated `OnboardingPermissionCoordinator`.

## Context

`OnboardingViewModel` (OnboardingViewModel.kt:117–439, 442 LOC) mixes page navigation state machine, mode/DNS selection persistence, permission resolution (VPN consent intent via `permissionPlatformBridge`, notification permission check), and validation lifecycle management. `onCleared()` performs dual teardown: cancels `validationJob` and calls `validationRunner.stopActiveValidation()` — two lifecycle hooks for one concern. The four responsibilities mean any change to the VPN permission flow touches the same class as pagination changes.

Source: `app/src/main/kotlin/com/poyka/ripdpi/activities/OnboardingViewModel.kt:117-439`

## Acceptance criteria

- [ ] `OnboardingPermissionCoordinator` class owns VPN consent intent creation and notification permission checks, returning a sealed `PermissionResult` to the ViewModel.
- [ ] `OnboardingViewModel` retains page navigation state machine and delegates permission and validation lifecycle to injected coordinators.
- [ ] `onCleared()` in `OnboardingViewModel` reduced to a single responsibility (cancel validation job only).
- [ ] Constructor params drop from 5 to ≤3 after extraction.
- [ ] Onboarding UI tests pass.

## Definition of done

`OnboardingViewModel` LOC < 250; `OnboardingPermissionCoordinator` has its own unit tests; `onCleared()` has a single concern.
