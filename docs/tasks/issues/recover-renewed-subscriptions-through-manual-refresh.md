---
id: DAT-1791480001436232
title: Recover renewed subscriptions through manual refresh
kind: bug
status: review
area: data
priority: medium
owner: Subscription recovery writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: recover-renewed-subscriptions
created: 2026-10-08
updated: 2026-10-08
---

## Goal

A user can recheck a renewed subscription at the same URL after local expiry or terminal failure.

## Acceptance criteria

Manual refresh retries cached expiry and terminal failures, but still rejects expired response payloads. Background refresh and bootstrap replay policy stay unchanged.

## Ownership

Subscription recovery writer owns app SubscriptionRefreshCoordinator.kt and SubscriptionRecoveryTest.kt, SubscriptionStatusScreen.kt, and SubscriptionStatusScreenTest.kt in this isolated worktree. No runtime parser, AWG repository, locale, schema, or lockfile edits. Other writers own separate core paths; the parent serializes generated board integration.
