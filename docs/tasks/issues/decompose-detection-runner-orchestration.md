---
title: Decompose detection runner orchestration
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Decompose detection runner orchestration #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

Split `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/DetectionRunner.kt` so detection contracts, adapters, pipeline orchestration, progress reporting, default result shaping, verdict assembly, and DI bindings are not all in one file.

## Context

`DefaultDetectionCheckRunner.run` coordinates every enabled checker, disabled-result fallback, async scheduling, progress updates, verdict evaluation, and output shaping. The same file also contains port interfaces, default adapters, and Hilt bindings.

## Acceptance criteria

- [ ] Move public detection contracts and stage/result defaults into focused files.
- [ ] Move checker port adapters out of the runner file.
- [ ] Extract pipeline scheduling/progress handling from verdict/result assembly.
- [ ] Move DI bindings into a dedicated module file.
- [ ] Existing detection unit tests remain green and cover the split orchestration path.

## Completion outcome

Closing this task means detection has a clear pipeline boundary: contracts, checker adapters, scheduling/progress, verdict assembly, disabled-result defaults, and DI are independently reviewable.

## Regression guardrails

- Do not create a new all-in-one `DetectionPipeline` class that owns every checker and output shape.
- Do not let DI modules contain orchestration logic or checker adapters contain verdict policy.
- Do not add new detection checks by editing central scheduling, defaults, and verdict code together.
- Do not close the task without tests for the orchestration path and at least one disabled-check path.
- Do not close the task without focused unit tests for each extracted detection orchestration component.

## Links

- [[Epic - Post-refactor architecture cleanup]]
