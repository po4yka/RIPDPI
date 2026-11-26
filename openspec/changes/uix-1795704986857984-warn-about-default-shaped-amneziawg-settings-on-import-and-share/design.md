## Context

Portfolio task `UIX-1795704986857984` owns this change. Importing or sharing an AmneziaWG config warns the user when its handshake looks like plain WireGuard, when its header values are defaults, or when its I1 packet equals a publicly known default.

## Goals / Non-Goals

- Goal: deliver `Warn about default-shaped AmneziaWG settings on import and share` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. Where the list of known default digests lives, and how it is updated, is open design work.
- The lint must not suggest specific replacement values. It tells the user that the value is shared by many servers and that the server owner should generate its own.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
