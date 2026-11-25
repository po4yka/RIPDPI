## Context

Portfolio task `SVC-1795615846684850` owns this change. A new user who passes the onboarding connection test sees a healthy connection on the home screen, not a route warning caused by start-up noise.

## Goals / Non-Goals

- Goal: deliver `Stop showing Route issue right after a passed connection test` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
