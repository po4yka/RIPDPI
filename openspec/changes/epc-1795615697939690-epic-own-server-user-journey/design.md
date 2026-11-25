## Context

Portfolio task `EPC-1795615697939690` owns this change. A new user without a preset VPN configuration can find, add, configure, start, monitor, and manage their own server.

## Goals / Non-Goals

- Goal: deliver `Guide new users from install to their own VPN server` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
