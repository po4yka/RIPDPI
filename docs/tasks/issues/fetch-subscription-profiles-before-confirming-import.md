---
id: DAT-1791480858535857
title: Fetch subscription profiles before confirming import
kind: bug
status: done
area: data
priority: medium
owner: Subscription import writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fetch-subscription-on-confirm
created: 2026-10-08
updated: 2026-10-08
closed_at: "2026-10-08T18:07:15Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Independent review approved; 67 app tests and lint passed. Combined 7a4a87a9 passed 269 tests, staticAnalysis, architecture health, and locked Cargo metadata.
---

## Goal

The confirmed Add to RIPDPI action fetches and saves ordinary subscription profiles before reporting success.

## Acceptance criteria

Real coordinator tests prove no pre-confirm HTTP, durable profiles before success, explicit failure on rejected or empty content, same-URL recovery, AWG-only success, and cancellation without a false success event. Consumed bootstrap URLs are never fetched again.

## Ownership

Subscription import writer owns SubscriptionImportConfirmViewModel.kt, SubscriptionInitialImportTest.kt, and the subscription cases in ImportConfirmViewModelTest.kt. Other writers own AWG mapper/repository and parser fingerprints. No schema, locale, native, or lockfile edits; parent handles combined board integration.

## Final evidence ownership

The subscription evidence worker owns verification records and task closure receipts on `docs/subscription-p2-final`. The integration owner provides the final gate results and serializes main integration. Source, tests, golden images, and generated artifacts remain with their assigned owners.
