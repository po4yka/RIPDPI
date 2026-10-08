## Context

refresh(groupId) is called by the status UI; refreshAll is the WorkManager path. Both call refreshGroup, which currently stops for cached terminal failure and expiry.

## Goals / Non-Goals

- Goal: an explicit recheck can observe same-URL server renewal.
- Non-goal: automatic retry of revoked credentials or bootstrap URLs; relaxing payload expiry, HTTPS mirror, redirect, or size checks.

## Decisions

Pass an explicit private manual flag to refreshGroup. Only cached state guards depend on this flag. The fetch sends unconditional GET requests already; no validators or cached 304 success are introduced. Successful current payloads use the existing atomic group persistence path.

## Contracts and ownership

Subscription recovery writer owns app coordinator and SubscriptionRecoveryTest.kt, SubscriptionStatusScreen.kt, and SubscriptionStatusScreenTest.kt. Other writers own AWG and parser files; parent owns combined board integration. No JNI, protobuf, storage schema, locale, lockfile, or native changes.

## Risks / Trade-offs

A user may intentionally retry revoked credentials. Each action is bounded by existing endpoint and payload limits. Failed attempts preserve members. Background policy stays unchanged.

## Migration Plan

No migration. Existing rows can recover through explicit refresh. Run SubscriptionRecoveryTest, SubscriptionRefreshDeliveryTest, SubscriptionRefreshCoordinatorTest, and SubscriptionAutoUpdateWorkerTest; run affected app lint and detekt. Revert this commit to restore old behavior.

The status screen exposes its existing refresh button for expired and invalidated long-lived subscriptions. Bootstrap remains non-refreshable. Existing replacement guidance and design tokens remain unchanged. No matching subscription RDS preview exists; existing Compose components and Robolectric click tests are the design reference. No golden fixtures change.
