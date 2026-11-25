## Context

Portfolio task `UIX-1795615852980919` owns this change. The user sees, adds, selects, and manages every saved server in one list, with one name for servers and another for DPI presets.

## Goals / Non-Goals

- Goal: deliver `Manage all servers in one list` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
