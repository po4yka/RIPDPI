---
id: DAT-1791480001436232
title: Recover renewed subscriptions through manual refresh
kind: bug
status: done
area: data
priority: medium
owner: Subscription recovery writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: recover-renewed-subscriptions
created: 2026-10-08
updated: 2026-10-08
closed_at: "2026-10-08T18:15:31Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Manual recovery source tests and static checks passed; authorized expired-theme snapshots verified with 5 screenshot tests and 10 images at 151c8ea881a21e765717d8e86c5261204a61b9d6.
---

## Goal

A user can recheck a renewed subscription at the same URL after local expiry or terminal failure.

## Acceptance criteria

Manual refresh retries cached expiry and terminal failures, but still rejects expired response payloads. Background refresh and bootstrap replay policy stay unchanged.

## Ownership

Subscription recovery writer owns app SubscriptionRefreshCoordinator.kt and SubscriptionRecoveryTest.kt, SubscriptionStatusScreen.kt, and SubscriptionStatusScreenTest.kt in this isolated worktree. No runtime parser, AWG repository, locale, schema, or lockfile edits. Other writers own separate core paths; the parent serializes generated board integration.

## Final evidence ownership

The subscription evidence worker owns verification records and task closure receipts on `docs/subscription-p2-final`. The integration owner provides the final gate results and serializes main integration. Source, tests, golden images, and generated artifacts remain with their assigned owners.

The snapshot writer owns only `SubscriptionStatusScreenshotTest.expired_light.png` and `SubscriptionStatusScreenshotTest.expired_dark.png` on `fix/subscription-recovery-snapshots`, plus the local snapshot verification evidence. The user authorized these two baseline updates after review of their expected, actual, and comparison images. Task closure remains with the integration owner.
