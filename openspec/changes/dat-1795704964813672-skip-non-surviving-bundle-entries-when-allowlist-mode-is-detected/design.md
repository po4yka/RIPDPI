## Context

Portfolio task `DAT-1795704964813672` owns this change. When allowlist mode is detected, failover skips bundle entries whose allowlist-regime survivability label says they do not survive, and treats unlabeled entries as neutral.

## Goals / Non-Goals

- Goal: deliver `Skip non-surviving bundle entries when allowlist mode is detected` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. The task is blocked until the server counterpart publishes the label definition and the allowlist verdict exists.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
