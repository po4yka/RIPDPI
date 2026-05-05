---
title: Extract LogEntryMapper and LogAggregatorUseCase from LogsViewModel
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Extract LogEntryMapper and LogAggregatorUseCase from LogsViewModel #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Move log formatting and multi-source aggregation out of `LogsViewModel` into a `LogEntryMapper` and `LogAggregatorUseCase`, and fix the thread-unsafe `SimpleDateFormat` usage.

## Context

`LogsViewModel` (LogsViewModel.kt:133–394, 483 LOC) aggregates logs from four sources, filters them, formats timestamps via `SimpleDateFormat` instantiated inline on every call (not thread-safe), and observes a service lifecycle state machine. File-level extension functions `NativeRuntimeEvent.toRuntimeLogEntry` and `DiagnosticEvent.toDiagnosticLogEntry` (lines 401–440) contain formatting logic that belongs in a mapper layer.

Source: `app/src/main/kotlin/com/poyka/ripdpi/activities/LogsViewModel.kt:133-394`

## Acceptance criteria

- [ ] `LogEntryMapper` class owns all event-to-`LogEntry` conversions; uses `DateTimeFormatter` (thread-safe) instead of `SimpleDateFormat`.
- [ ] `LogAggregatorUseCase` merges the four log sources (`manualLogBuffer`, `serviceLifecycleBuffer`, `runtimeEventLogs`, `diagnosticsEventLogs`), applies deduplication and cap logic.
- [ ] `LogsViewModel` exposes `uiState` and delegates filtering to `LogAggregatorUseCase`; constructor params reduced to ≤3.
- [ ] No `SimpleDateFormat` construction inside ViewModel or extension functions.
- [ ] Logs screen UI tests pass.

## Definition of done

`LogsViewModel` LOC < 200; zero `SimpleDateFormat` in production log paths; `LogEntryMapper` has unit tests.
