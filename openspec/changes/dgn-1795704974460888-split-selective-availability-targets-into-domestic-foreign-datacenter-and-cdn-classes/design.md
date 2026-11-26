## Context

Portfolio task `DGN-1795704974460888` owns this change. Freeze diagnostics can show whether connection_freeze results cluster by destination class and by destination AS, because the selective-availability targets carry a class.

## Goals / Non-Goals

- Goal: deliver `Split selective-availability targets into domestic, foreign datacenter and CDN classes` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. The target list needs hosts in each class that the project may probe; choosing them is design work and uses no private endpoints.
- The destination-AS claim stays a hypothesis until a report set shows it. The task must make the claim testable, not assume it.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
