---
id: DAT-1793949941293491
title: Commit profile data atomically with Room
kind: feature
status: done
area: data
priority: high
owner: Profile persistence maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: profile-room-persistence
created: 2026-11-06
updated: 2026-11-06
closed_at: "2026-11-06T15:24:26Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Room transactions, migration, encryption and Stop race checks passed; Android Keystore persistence test passed. Final UI unit regressions and source gates passed. Integrated and pushed main at 963cca31c2e01d941c572cc3182813dcdc5b3be3. User waived hosted CI verification. Final UI APK packaging and device rerun remain unverified after a local Gradle packaging deadlock.
---

## Goal

Store each profile mutation, including credentials, selection, groups and settings,
in one Room transaction. Remove the application write-ahead journal and
compensating persistence writes. Preserve encryption and Stop precedence.

## Acceptance criteria

- Failure or cancellation before commit preserves the complete previous state; a successful
  commit exposes the complete new state after reopen.
- Backup snapshot and restore use database transactions, without retry-based
  consistency or rollback after-images.
- Existing data and pending legacy mutations migrate once without secret loss.
- Database rows and WAL contain no plaintext secrets. Direct-boot state remains
  available before credential unlock.
- Targeted persistence, migration, cancellation and activation-race tests pass;
  affected Kotlin tests, static analysis and CI source gates pass.
- Commit the implementation, integrate into main and push. The user explicitly
  waived hosted CI waiting and verification on 2026-11-06.

## Ownership

The primary writer owns this worktree and all schema, DI and transaction changes.
Read-only agents map storage and review the transaction boundary. Any later writer
must use a separate worktree with an explicit path assignment.

The consumer writer owns the activity startup/readiness sources and their app
unit tests in an isolated worktree for the hosted UI regression. The primary
writer owns integration, instrumentation diagnostics and task evidence. Review
agents remain read-only; schema, locale and baseline files stay with the primary writer.
