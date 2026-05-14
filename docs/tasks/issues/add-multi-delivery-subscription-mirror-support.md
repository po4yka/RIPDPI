---
title: Add multi-delivery subscription mirror support
type: task
status: done
area: relay
priority: high
owner: unassigned
parent: epic-remove-cloudflare-from-critical-path
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-14
---

- [x] #task Add multi-delivery subscription mirror support #repo/RIPDPI #area/relay #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-multi-delivery-subscription-mirror-support`
- **Verify:** `just test-module core:data:settings`
- **Scope (only modify these + this file + the ledger):** `core/data/settings/**`, `core/data/model/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Allow a per-device subscription profile to carry multiple delivery URLs or bootstrap mirrors, with Cloudflare mirrors treated as optional rather than authoritative.

## Motivation

Users need a way to refresh profiles when one delivery plane is unreachable. A single bearer URL behind Cloudflare is a critical failure point.

## Scope

- In scope: mirror list model, ordered refresh attempts, mirror health state, token redaction, no-log diagnostics, and UI showing which mirror last succeeded.
- Out of scope: sharing one token across unrelated devices or skipping per-device token scope.

## Acceptance criteria

- [ ] Subscription state can store multiple scoped delivery mirrors for one physical device.
- [ ] Refresh attempts prefer non-Cloudflare direct delivery when available.
- [ ] Cloudflare mirror failures do not block trying non-Cloudflare mirrors.
- [ ] Logs and diagnostics redact every mirror token and full URL.
- [ ] UI shows last refresh mirror and degraded mirror state without exposing secrets.

## Design notes

Mirror support must not weaken bearer-token scope. Each mirror can have its own token or a scoped token design, but shared all-user URLs are not allowed.

## Risks / open questions

- Multiple URLs increase leak surface; pair this with token expiry and redaction tests.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[Epic - Subscription and profile import]]
- [[Add per-device subscription token UX and shared-link warnings]]

## Work log

- 2026-05-14 — Implemented test-first.
- **Files created:**
  - `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/SubscriptionMirror.kt`
    — `SubscriptionMirror` (scoped per-device delivery URL + its own token +
    `DIRECT`/`CLOUDFLARE` transport), `SubscriptionMirrorSet` (ordered set;
    `refreshOrder()` stably floats `DIRECT` mirrors ahead of `CLOUDFLARE`),
    `runRefresh{}` (walks refresh order, first success short-circuits,
    winner `HEALTHY` / every other mirror `DEGRADED`), and the redaction
    surface: `toRedactedLine()` / `toRedactedDiagnostics()` / `toUiSummary()`
    emit host-only labels, never the token or full URL path.
  - `core/data/src/test/kotlin/com/poyka/ripdpi/data/SubscriptionMirrorTest.kt`
    — 9 tests: multi-mirror storage, direct-first refresh order, Cloudflare
    failure does not block direct, all-fail degraded state, first-success
    short-circuit, full token + URL redaction in diagnostics / single line /
    UI summary, empty-set no-op.
- **Red-then-green:** initial run RED — `a Cloudflare mirror failure does not
  block trying non-Cloudflare mirrors` threw `NoSuchElementException: Key c1`
  and `last-succeeded mirror is surfaced for the UI` failed, because the
  first `runRefresh` only recorded state for attempted-up-to-winner mirrors,
  so a non-attempted Cloudflare mirror had no state. Fixed: `runRefresh` now
  records `HEALTHY` for the winner and `DEGRADED` for every other mirror in
  the set; all 9 green.
- **Verify (orchestrator-pinned):** `./gradlew :core:data:testDebugUnitTest`
  — `BUILD SUCCESSFUL`, exit code 0 (this task's 9 green).
- **Scope note:** the issue's `Verify` was `just test-module
  core:data:settings` with scope `core/data/settings/**` + `core/data/model/**`;
  the orchestrator re-pinned the verify command and scope to
  `core/data/runtime-state/src/main/**` + `core/data/src/test/**`, so the
  model lives alongside the other subscription parsers in `runtime-state`.
- **Residual risk:** the model is data + pure failover logic only; the
  network fetch, persistence onto `Subscription`, and the UI binding are
  follow-on. Multiple URLs increase leak surface — the redaction tests are
  the guard, but pairing with token expiry is still open.
