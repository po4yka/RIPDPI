## Context

Portfolio task `UIX-1795661310559744` owns this change. The user can share a saved AmneziaWG server as a complete `.conf` file, from the same server actions menu that relay servers use.

## Goals / Non-Goals

- Goal: deliver `Share AmneziaWG servers as full .conf files` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
