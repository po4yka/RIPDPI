## Context

Portfolio task `DNS-1795705017215412` owns this change. The ECH and DNS survey and the CDN ECH updater do not treat a failure of every measurement as a signal of blocking.

## Goals / Non-Goals

- Goal: deliver `Keep universal DNS and ECH check failures out of block verdicts` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. If the verification step shows that no code path treats a universal failure as a block, close the task with that evidence and keep only the regression tests.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
