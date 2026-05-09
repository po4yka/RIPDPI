---
title: Split strategy pack service workflows
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split strategy pack service workflows #repo/RIPDPI #area/service #status/backlog 🔼

## Summary

Split `core/service/src/main/kotlin/com/poyka/ripdpi/strategy/StrategyPackService.kt` so settings observation, refresh orchestration, scheduling/backoff, repository refresh, selection projection, and state publication are separate workflows.

## Context

`DefaultStrategyPackService` currently initializes state, observes settings, schedules refreshes, performs manual refresh, handles backoff, refreshes repository data, projects the selected pack, and publishes service state in one class.

## Acceptance criteria

- [ ] Extract settings observer and selected-pack projection.
- [ ] Extract refresh executor and repository coordination.
- [ ] Extract scheduler/backoff policy.
- [ ] Keep the public `StrategyPackService` API stable.
- [ ] Add unit tests for refresh scheduling and selected-pack projection.

## Completion outcome

Closing this task means strategy-pack service behavior is split into settings observation, refresh execution, scheduler/backoff, repository coordination, selected-pack projection, and state publication workflows.

## Regression guardrails

- Do not keep refresh, scheduling, settings collection, and UI/service-state projection in one implementation class.
- Do not let scheduler/backoff policy depend on repository DTO details.
- Do not make selected-pack projection mutate refresh state or settings observers.
- Do not close the task without tests for refresh scheduling and projection behavior.
- Do not close the task without focused unit tests for each extracted settings-observer, refresh, scheduler, projection, and publication workflow.

## Links

- [[Epic - Post-refactor architecture cleanup]]
