---
id: DAT-1792728666702112
title: Fence subscription ABA and recover every commit boundary
kind: bug
status: done
area: data
priority: high
owner: Subscription persistence owner
parent: null
blocked_by: []
spec_mode: required
openspec_change: subscription-generation-recovery
created: 2026-10-23
updated: 2026-10-23
closed_at: "2026-10-23T05:46:12Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Persisted group generations reject ABA; 40 fault-matrix cases and competing recovery edits pass. Local 4247 unit tests and static gates passed; PR 652 CI run 39903731858 and ci-required passed, including all Debug/Release channels and five Android API levels. Implementation merged to main at 31c8ea8250dce7374a27820d67ba39bb502b15f5.
---

## Goal

Reject delayed subscription responses after identical group delete/recreate, edit/revert or restore, and recover every partially accepted AWG refresh without losing group publication or local keys.

## Acceptance criteria

- Persist a new group ownership generation for each effective edit, replacement and accepted refresh; exact journal replay preserves it.
- Migrate existing encrypted groups and pending subscription after-images durably before exposing ownership or writing AWG data.
- Exercise failure and cancellation at every prepare, credential, row, group and completion boundary with the shared production coordinator, repeated recovery and a competing edit.
- Verify existing collectors without list(), utility readiness, stable AWG IDs/private keys, unrelated groups and absence of stale writes.
- Pass affected unit suites, static analysis, architecture/task checks and required hosted CI before merge. No physical network or deployment acceptance is owned here.

## Ownership

The subscription persistence owner exclusively writes production code, shared test support, existing tests, and this task/specification. A separate test worker owns the new core/data SubscriptionRefreshFaultMatrixTest.kt, SubscriptionOwnershipMigrationTest.kt and runtime-state ProfilePreferenceDurabilityTest.kt in its own worktree; the owner integrates them sequentially before combined validation. Independent reviewers are read-only. No dependency, native runtime, locale or baseline changes are planned.
