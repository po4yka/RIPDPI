---
id: DAT-1791480858535857
title: Fetch subscription profiles before confirming import
kind: bug
status: review
area: data
priority: medium
owner: Subscription import writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fetch-subscription-on-confirm
created: 2026-10-08
updated: 2026-10-08
---

## Goal

The confirmed Add to RIPDPI action fetches and saves ordinary subscription profiles before reporting success.

## Acceptance criteria

Real coordinator tests prove no pre-confirm HTTP, durable profiles before success, explicit failure on rejected or empty content, same-URL recovery, AWG-only success, and cancellation without a false success event. Consumed bootstrap URLs are never fetched again.

## Ownership

Subscription import writer owns SubscriptionImportConfirmViewModel.kt, SubscriptionInitialImportTest.kt, and the subscription cases in ImportConfirmViewModelTest.kt. Other writers own AWG mapper/repository and parser fingerprints. No schema, locale, native, or lockfile edits; parent handles combined board integration.
