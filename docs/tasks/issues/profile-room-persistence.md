---
id: DAT-1793949941293491
title: Commit profile data atomically with Room
kind: feature
status: doing
area: data
priority: high
owner: Profile persistence maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: profile-room-persistence
created: 2026-11-06
updated: 2026-11-06
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
- Commit the implementation, integrate into main and push, then observe hosted CI.

## Ownership

The primary writer owns this worktree and all schema, DI and transaction changes.
Read-only agents map storage and review the transaction boundary. Any later writer
must use a separate worktree with an explicit path assignment.
