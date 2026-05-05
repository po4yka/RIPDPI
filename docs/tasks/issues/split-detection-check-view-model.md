---
title: Split DetectionCheckViewModel and fix AndroidViewModel misuse
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

- [ ] #task Split DetectionCheckViewModel and fix AndroidViewModel misuse #repo/RIPDPI #area/ui #status/backlog ⏫

## Objective

Migrate `DetectionCheckViewModel` from `AndroidViewModel(application)` to a standard `ViewModel`, extract HTTP and persistence concerns into UseCases, and inject `CommunityComparisonStore` as a Hilt singleton.

## Context

`DetectionCheckViewModel` (DetectionCheckViewModel.kt:65–392) extends `AndroidViewModel(application)`, bypassing `@ApplicationContext`. It mixes: permission checks via `ContextCompat.checkSelfPermission` directly in the ViewModel, community stats HTTP (`CommunityComparisonClient` constructed inline), detection run orchestration, history persistence (`historyStore.save()`/`loadHistory()` inline), and settings auto-fix (`AppSettings` proto mutation). `CommunityComparisonStore` is instantiated via `by lazy` using the raw `Application` at line 84, creating an untracked singleton (also see companion task for `CommunityComparisonStore`).

Source: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionCheckViewModel.kt:65-392`

## Acceptance criteria

- [ ] `DetectionCheckViewModel` extends `ViewModel` (not `AndroidViewModel`); `@ApplicationContext` injected via Hilt where a context is needed.
- [ ] `DetectionPermissionHelper` standalone class owns `ContextCompat.checkSelfPermission` calls.
- [ ] `CommunityStatsUseCase` owns `CommunityComparisonClient` construction and cache reads.
- [ ] History persistence delegated to `DetectionHistoryRepository` interface (see companion task).
- [ ] `CommunityComparisonStore` injected as `@Singleton` via Hilt (see companion task).
- [ ] No `by lazy { SomeClass(application) }` patterns remain in the ViewModel.
- [ ] Existing detection screen tests pass.

## Definition of done

`DetectionCheckViewModel` extends `ViewModel`; LOC < 250; no direct `Application` usage; all permission and HTTP concerns delegated.
