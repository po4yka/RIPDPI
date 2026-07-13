---
title: "Unify root helper process ownership"
type: task
status: review
area: service
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Ensure every start and stop path addresses the same process-scoped `RootHelperManager` owner.

## Acceptance criteria

- DI identity coverage proves resolver, session, and service receive the same owner.
- Teardown terminates the process and removes its socket through that owner.
- `:core:service:testDebugUnitTest` passes.

## Work log

- Scoped the constructor-injected `RootHelperManager` as a process singleton so parent and service-session Hilt components share the same process owner.
- Added a regression contract for the DI scope; Hilt compilation exercises all production injection sites.
- Verified with the focused service unit suite.
