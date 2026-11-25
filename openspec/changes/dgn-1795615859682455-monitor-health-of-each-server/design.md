## Context

Portfolio task `DGN-1795615859682455` owns this change. The user can check whether each server works and see how the connected server performs.

## Goals / Non-Goals

- Goal: deliver `Monitor the health of each server` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
