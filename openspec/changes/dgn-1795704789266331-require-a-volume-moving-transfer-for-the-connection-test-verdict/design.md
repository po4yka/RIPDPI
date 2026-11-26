## Context

Portfolio task `DGN-1795704789266331` owns this change. A connection test that reports Passed has moved a payload larger than the TCP-freeze threshold and checked it byte-exact, not only a handshake or a small request.

## Goals / Non-Goals

- Goal: deliver `Require a volume-moving transfer for the connection test verdict` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. The payload size must exceed the freeze threshold that the transfer-volume work already uses. Choose the value from that work, not a new constant.
- Open question for design: whether the onboarding test and the saved-server check share one verdict model, and how a volume-not-tested state is shown without a new locale set.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
