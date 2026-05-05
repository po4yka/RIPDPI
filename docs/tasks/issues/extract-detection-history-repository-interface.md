---
title: Extract DetectionHistoryRepository interface and fix synchronous IO in DetectionHistoryStore
type: task
status: backlog
area: data
priority: high
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Extract DetectionHistoryRepository interface and fix synchronous IO in DetectionHistoryStore #repo/RIPDPI #area/data #status/backlog ⏫

## Objective

Introduce a `DetectionHistoryRepository` interface, bind it with Hilt `@Binds`, and wrap all `SharedPreferences` reads/writes in `withContext(Dispatchers.IO)`.

## Context

`DetectionHistoryStore` (DetectionHistoryStore.kt:27–69) is a `@Singleton` with no interface. It reads/writes `SharedPreferences` synchronously in `save()`, `load()`, `findByFingerprint()`, and `latestEntries()`. `DetectionCheckViewModel.startCheck()` calls these inside `viewModelScope.launch` with no `withContext(Dispatchers.IO)` — this performs file I/O on the main thread. `DetectionCheckViewModel` injects the concrete class directly, blocking testability without Hilt component manipulation.

Source: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/DetectionHistoryStore.kt:27-69`

## Acceptance criteria

- [ ] `interface DetectionHistoryRepository` defined with `suspend fun save(...)`, `suspend fun loadLatest(...)`, `suspend fun findByFingerprint(...)`.
- [ ] `DetectionHistoryStore` implements the interface; all `SharedPreferences` I/O wrapped in `withContext(Dispatchers.IO)`.
- [ ] Hilt `@Binds` module in `:core:detection` wires `DetectionHistoryStore` as `DetectionHistoryRepository`.
- [ ] `DetectionCheckViewModel` injects `DetectionHistoryRepository`, not the concrete store.
- [ ] Unit tests for `DetectionHistoryStore` mock the dispatcher to verify IO context.

## Definition of done

No synchronous `SharedPreferences` calls on calling thread; interface bound via `@Binds`; ViewModel injects interface.
