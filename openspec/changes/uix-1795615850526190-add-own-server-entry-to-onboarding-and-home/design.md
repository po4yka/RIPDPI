## Context

Portfolio task `UIX-1795615850526190` owns this change. A new user who has their own VPN server finds how to add it during onboarding and from the home and Connection screens.

## Goals / Non-Goals

- Goal: deliver `Add an own-server entry point to onboarding and home` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- The implementer records design decisions here before code changes start.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
