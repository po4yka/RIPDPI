## Context

Portfolio task `DGN-1795705002630350` owns this change. For a relay chain that the user built on the client, diagnostics shows the first hop where a large transfer stops.

## Goals / Non-Goals

- Goal: deliver `Locate the stalling hop of a user-built relay chain with a large transfer` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. Priority is low because it needs the connection test task first and serves only users who build chains themselves.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
