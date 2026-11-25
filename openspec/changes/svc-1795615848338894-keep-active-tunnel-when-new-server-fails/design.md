## Context

Portfolio task `SVC-1795615848338894` owns this change. A failed attempt to connect to a new server does not leave the user without the connection they had, and the error tells them what to check.

## Goals / Non-Goals

- Goal: deliver `Keep the working tunnel when a new server fails to start` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
