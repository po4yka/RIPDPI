---
title: Inject CommunityComparisonStore as @Singleton via Hilt
type: task
status: backlog
area: data
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Inject CommunityComparisonStore as @Singleton via Hilt #repo/RIPDPI #area/data #status/backlog 🔼

## Objective

Add `@Inject constructor` and `@Singleton` to `CommunityComparisonStore` and remove ad-hoc manual instantiation from `DetectionCheckViewModel` and `SettingsViewModel`.

## Context

`CommunityComparisonStore` has no `@Inject`, no `@Singleton`. It is instantiated at two sites: `DetectionCheckViewModel:84` via `by lazy { CommunityComparisonStore(application) }` and `SettingsViewModel:158` via `CommunityComparisonStore(settingsUiDependencies.application).clear()`. Each creates a separate `SharedPreferences` handle, rendering the `CACHE_TTL_MS = 3600_000L` TTL check meaningless (read and write happen on different instances). `SettingsViewModel` in `:app` directly instantiates a type from `:core:detection` — a cross-module boundary violation.

Source: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/community/CommunityComparisonStore.kt`

## Acceptance criteria

- [ ] `CommunityComparisonStore` annotated `@Singleton` with `@Inject constructor(@ApplicationContext context: Context)`.
- [ ] Hilt module in `:core:detection` provides or binds `CommunityComparisonStore` (or a `CommunityComparisonRepository` interface if testability requires it).
- [ ] `by lazy { CommunityComparisonStore(application) }` in `DetectionCheckViewModel` replaced with injected field.
- [ ] `CommunityComparisonStore(settingsUiDependencies.application).clear()` in `SettingsViewModel` replaced with the injected singleton.
- [ ] Cache TTL behavior correct: single instance shared across both consumers.
- [ ] Existing detection and settings tests pass.

## Definition of done

Zero manual `CommunityComparisonStore(...)` instantiations outside Hilt; single instance across the process; tests green.
