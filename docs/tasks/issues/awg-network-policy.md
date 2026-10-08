---
id: DAT-1791479988786516
title: Preserve imported AWG DNS and route policy
kind: bug
status: done
area: data
priority: high
owner: awg-network-policy
parent: null
blocked_by: []
spec_mode: required
openspec_change: preserve-awg-network-policy
created: 2026-10-08
updated: 2026-10-08
closed_at: "2026-10-08T18:07:14Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Independent review approved; 113 policy tests and lint passed. Final 7a4a87a9 passed 269 tests, staticAnalysis, architecture health, and locked Cargo metadata.
---

## Goal

Keep DNS servers and IPv4/IPv6 routes from imported WireGuard and AWG profiles through storage and activation.

## Acceptance criteria

- Import retains explicit DNS, AllowedIPs, and address families.
- Refresh updates supplied policy, preserves absent policy, and never widens explicit empty routes.
- Regression, parser, repository, and consumer gates pass.

## Ownership

The awg-network-policy writer owns WireGuardActivationMappers.kt, WireGuardIniSubscriptionParser.kt and its models, AwgSubscriptionProfile.kt, AwgProfileRepository.kt, and related core data tests. It owns network-policy presence constructor arguments and strict optional string-list decode guards in SingBoxSubscriptionParser.kt; the fingerprint writer owns separate validation code in that file. The writer also owns AwgActivationRequest.kt and its tests, VpnProfileInterface.kt, the effective DNS call in RipDpiVpnService.kt, related service tests, and a Simple ConfigSeeder regression test. No app coordinator, UI, native, wire schema, WireGuardConfig, locale, golden, baseline, or dependency edits. The parent reconciles independent worktrees and serializes Gradle and integration.

## Final evidence ownership

The subscription evidence worker owns verification records and task closure receipts on `docs/subscription-p2-final`. The integration owner provides the final gate results and serializes main integration. Source, tests, golden images, and generated artifacts remain with their assigned owners.
