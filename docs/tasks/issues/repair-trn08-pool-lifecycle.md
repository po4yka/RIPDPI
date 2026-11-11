---
id: TRN-1794387054491059
title: Repair TRN-08 pool admission and lifecycle
kind: bug
status: doing
area: transport
priority: high
owner: Pool repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-trn08-pool-lifecycle
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Describe the observable outcome.

## Acceptance criteria

Define verifiable completion criteria.

## Parallel ownership

- Idle worker: F2 relay-mux state, health, lease, pool reservation and owning tests; isolated worktree.
- Dial worker: F1 opening admission after F2, then H3 independent datagram factory path in a separate commit; isolated worktree.
- Cleanup worker: F3 SessionRegistry resource/error ownership and tests; isolated worktree.
- Root: H1 terminal shutdown, H2 runtime teardown verification, OpenSpec/task lifecycle, generated API snapshots, combined gates, review and integration.
- Serialized lane: relay-mux pool.rs is applied F2 then F1 then H3/H1. No worker edits shared manifests, lockfiles, schemas, locales, goldens or generated snapshots.

## Acceptance criteria

Each confirmed audit defect has a separate Conventional Commit, a behavior regression, and independent review. All local owning tests and ci-gates pass. main receives the commits by fast-forward and origin/main is observed at the pushed revision. Physical and external interoperability checks are outside this repair.
