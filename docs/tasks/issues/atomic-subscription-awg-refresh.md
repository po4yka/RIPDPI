---
id: DAT-1792683530998740
title: Commit subscription AWG rotations under current group ownership
kind: bug
status: doing
area: data
priority: high
owner: Client subscription maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: atomic-subscription-awg-refresh
created: 2026-10-22
updated: 2026-10-22
---

## Goal

Reject a stale subscription response before it can rotate AWG rows or credentials, and commit accepted AWG and group updates under the existing mutation owner.

## Acceptance criteria

- Delayed valid AWG responses cannot mutate a changed source, edited group, deleted group, or newer accepted generation.
- Accepted refresh rotates the existing AWG identity while preserving editor-owned key and network policy.
- The current group comparison and all writes share the existing recovery mutex; no check/write gap or nested recovery acquisition exists.
- Cancellation and write failure retain an encrypted journal that recovers the accepted full generation before another mutation.
- Affected module tests, static analysis, task contracts, architecture checks and full required CI pass before closure.

## Ownership

The client subscription maintainer owns app subscription coordination/tests, core-data AWG and recovery implementation/tests, runtime-state journal family, and this task/spec. Other worktrees and the pending acceptance branch are untouched. Shared mutation contracts have one writer; independent review is read-only.

## Scope and incident

Review found that `fetchAndParse` saved valid AWG profiles before the selector group compare-and-set. A delayed response could return `Superseded` after rotating credentials. The earlier offline-reserve design explicitly excluded AWG multi-store transactions; this bug extends that ownership guarantee without rewriting prior acceptance history. The original acceptance PR remains on hold until this fix is accepted. No deployment, credential use or physical-device acceptance is included.
