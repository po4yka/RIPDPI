## Context

Portfolio task `UIX-1795615858043815` owns this change. A user can create an AmneziaWG profile by hand without an external key tool and save it without connecting.

## Goals / Non-Goals

- Goal: deliver `Complete the AmneziaWG editor` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
