## Context

Portfolio task `UIX-1795615855953573` owns this change. After an import the user knows what was added and can connect to it in one step; a failed import tells them why.

## Goals / Non-Goals

- Goal: deliver `Confirm imports and offer to connect to the new server` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
