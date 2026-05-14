---
title: Add per-device subscription token UX and shared-link warnings
type: task
status: done
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-14
---

- [x] #task Add per-device subscription token UX and shared-link warnings #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-per-device-subscription-token-ux-and-shared-link-warnings`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`, `core/data/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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

## Work log

- 2026-05-14 — Implemented `app/src/main/kotlin/com/poyka/ripdpi/subscription/SubscriptionTokenUx.kt`,
  the client-side per-device-token logic:
  - `SubscriptionDetailUiState` + `subscriptionDetailUiState(subscription, revealSecrets)` —
    derives a detail view-state that treats a subscription as a device-scoped credential.
    The link and token are **redacted by default** (short non-reversible prefix; a blank
    secret renders as an explicit `(none)` placeholder) and only surfaced when the user
    explicitly opts in via `revealSecrets` — the redacted values are what screenshots /
    exports capture. It also exposes `lastUpdated`, `tokenExpiry`, `credentialExpiry`,
    `consumedAt`, `isBootstrap`, and `refreshable` (always `false` for a bootstrap token,
    consumed or not — a bootstrap URL must never be re-fetched), so a bootstrap token is
    presented distinct from a persistent long-lived subscription token.
  - `detectSharedLinkWarning(payload)` → `SharedLinkWarning.{None,Detected}` — a string-only
    heuristic that flags a payload as shared when one credential UUID is reused across
    >2 nodes (a strong "fleet-wide credential" signal). The wording is intentionally
    heuristic per the open question about third-party provider metadata.
  - `classifyRefreshFailure(httpCode, url)` → `SubscriptionRefreshFailure.{EXPIRED,REVOKED,RATE_LIMITED,UNREACHABLE}` —
    410→expired, 403→revoked, 429→rate-limited, everything else / transport failure →
    unreachable. The result is a **bare enum with no URL-bearing field**, so a refresh
    failure can be logged and surfaced without leaking the subscription source; `url` is
    accepted only so callers needn't strip it first.
- The bootstrap-vs-persistent distinction is also persisted end-to-end:
  `SubscriptionImportConfirmViewModel.confirm()` now stores `kind = BOOTSTRAP` when the add
  screen's bootstrap flag is set; the add screen already renders a one-time-link warning
  banner (`SubscriptionImportConfirmScreen`).
- TDD: `app/src/test/kotlin/com/poyka/ripdpi/subscription/SubscriptionTokenUxTest.kt` written
  first (default redaction; reveal-on-request; bootstrap distinct + non-refreshable;
  consumed-bootstrap timestamp; shared-UUID heuristic positive/negative; HTTP-code
  classification; failure enum carries no URL). Confirmed RED (unresolved
  `subscriptionDetailUiState` / `detectSharedLinkWarning` / `classifyRefreshFailure`), then
  GREEN. `ImportConfirmViewModelTest` extended to assert the persisted `kind`.
- Verify — contract command `just test-module app` maps to `./gradlew :app:testGithubDebugUnitTest`
  (the `:app` module has `play`/`fdroid`/`github` product flavors, so the plain
  `:app:testDebugUnitTest` task name is ambiguous; the `github` flavor was run and all
  flavors share the same `src/test` sources). Exit 0. `./gradlew :app:assembleDebug` exit 0.
- Not done within this scope: the no-secret-reveal *Compose UI* wiring beyond the existing
  import-confirm screen (there is no subscription list/detail screen in the app yet — only
  the import-confirm destination), and screenshot/export redaction enforcement in
  `core/diagnostics-data` (out of scope). The view-state mapper is the redaction primitive a
  future detail screen binds to.
