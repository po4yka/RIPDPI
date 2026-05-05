---
title: Remove :core:service test dependency on :core:detection (layer direction violation)
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Remove :core:service test dependency on :core:detection (layer direction violation) #repo/RIPDPI #area/service #status/backlog 🔼

## Objective

Remove `testImplementation(project(":core:detection"))` from `:core:service` and replace it with either a `:core:test-fixtures` module or in-module mocks so the L2 service layer does not pull the L3 detection layer even in test scope.

## Context

`:core:service` (L2) has `testImplementation(project(":core:detection"))` (L3) in `core/service/build.gradle.kts:110`. Even in test scope, a lower layer depending on a higher layer leaks the conceptual dependency and can create compile-time coupling if test helpers migrate from `testImplementation` to `implementation`.

Source: `core/service/build.gradle.kts:110`

## Acceptance criteria

- [ ] `testImplementation(project(":core:detection"))` removed from `:core:service`.
- [ ] Any test code that used `:core:detection` types refactored to use fakes/mocks defined within `:core:service/test` or a new `:core:test-fixtures` module at L0/L1.
- [ ] `:core:service` unit tests compile and pass without the `:core:detection` dep.
- [ ] No new upward layer dep introduced.

## Definition of done

`:core:service/build.gradle.kts` contains no reference to `:core:detection`; service tests green on CI.
