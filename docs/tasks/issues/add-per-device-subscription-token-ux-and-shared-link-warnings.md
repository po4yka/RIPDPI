---
title: Add per-device subscription token UX and shared-link warnings
type: task
status: backlog
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add per-device subscription token UX and shared-link warnings #repo/RIPDPI #area/vpn #status/backlog ⏫

## Summary

Add client UX and storage fields for per-device subscription tokens, expiry, rotation, and warnings when an imported subscription appears shared or unsafe.

## Motivation

Shared subscription URLs turn one leak into full-fleet credential exposure. RIPDPI should present subscriptions as device-scoped credentials with expiry and rotation state, not anonymous URL lists.

## Scope

- In scope: subscription detail fields, expiry/refresh state, token rotation state, one-time bootstrap import handling, shared-link warnings, and no-secret UI reveal behavior.
- Out of scope: implementing the remote delivery service or deciding provider billing policy.

## Acceptance criteria

- [ ] Subscription detail screen shows device ID, profile version, last refresh, token expiry, credential expiry, and assigned profile count without revealing secrets by default.
- [ ] Imported bootstrap tokens are marked distinct from persistent subscription tokens.
- [ ] App warns when a subscription payload appears to contain multiple users, shared UUIDs, or all-fleet profiles.
- [ ] Refresh failures distinguish expired, revoked, rate-limited, and unreachable states without logging the URL.
- [ ] Full URL, token, UUID, shortId, and passwords require explicit reveal and are redacted in screenshots/exports where possible.

## Design notes

This task is client-side only. Server-side delivery and token validation belong outside the Android app.

## Risks / open questions

- Third-party providers may not expose enough metadata to prove a token is per-device; warnings may need heuristic language.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - NekoBox subscription and profile import]]
- [[Add subscription auto-update WorkManager worker]]
