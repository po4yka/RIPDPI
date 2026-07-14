---
title: Fix July 2026 Android P1 audit findings
type: epic
status: review
area: epic
priority: critical
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-14
updated: 2026-07-14
---

## Goal

Eliminate every confirmed P1 defect from the July 2026 repeated Android and Compose audit with one regression-backed atomic commit per defect.

## Why now

The integrated application still has fail-open standalone AWG recovery, a nonfunctional bootstrap subscription path, unbounded subscription bodies, credential-bearing navigation and saved-state arguments, lifecycle-unsafe one-shot effects, unbounded editor state, a frame-rate recomposition hotspot, and an auth footer inset defect. Three accepted fixes also remain isolated on worktree branches instead of the combined remediation branch.

## Key decisions

- Build from current `origin/main` in `codex/fix-all-android-p1` and carry the three accepted atomic commits into this branch.
- Keep every remaining P1 fix and its regression tests in a separate Conventional Commit.
- Persist only secret-free identifiers for recoverable sessions and navigation; fail closed when rehydration fails.
- Keep navigation and UI events durable across lifecycle gaps without executing them while the Activity is stopped.
- Do not integrate, delete, or push the worktree branch without explicit user confirmation.

## Scope

- [x] Carry the credential-safe profile navigation commit.
- [x] Carry the cached telemetry sparkline geometry commit.
- [x] Carry the asynchronous profile-share QR commit.
- [x] Rehydrate standalone AWG by a persisted secret-free profile pointer and fail closed on missing state.
- [x] Consume bootstrap subscriptions exactly once and persist imported members before reporting success.
- [x] Bound subscription response bodies, profile counts, and raw configuration parsing.
- [x] Replace credential-bearing subscription navigation arguments with an opaque request token.
- [x] Keep backup PIN and PKCS#12 password out of Activity saved state.
- [x] Bound editor drafts, move domain compilation off the main thread, and keep large drafts out of saved state.
- [x] Deliver one-shot UI effects only in an active lifecycle while preserving pending events.
- [x] Move `StatusIndicator` frame-rate state reads to the draw/layer phase.
- [x] Apply navigation-bar insets to the shared authentication footer.

## Ship definition

- Twelve atomic fix commits exist, each retaining or adding focused regression coverage.
- Android release Kotlin compilation with Compose reports, static analysis, unit tests, architecture health, and relevant focused module tests pass on the combined branch.
- Process-death, payload-boundary, lifecycle, recomposition, and inset contracts are covered where host-side tests can exercise them; remaining device-only verification debt is reported explicitly.

## Work log

- 2026-07-14: Goal started in `codex/fix-all-android-p1` from `origin/main`; ownership assigned to Codex.
- 2026-07-14: Landed twelve atomic P1 fix commits plus focused static-analysis cleanup commits; no fix was combined with an unrelated P1.
- 2026-07-14: Verified `:app:compileGithubFullReleaseKotlin` with Compose reports, full `staticAnalysis`, aggregate `testDebugUnitTest`, full-tree architecture health, and `cargo metadata --locked`; all completed with exit code 0.
- 2026-07-14: Compose compiler reports classify `StatusIndicator`, `TelemetrySparkline`, and the profile-share composables as restartable and skippable. Device-only visual verification of auth insets remains appropriate before release but is not a host-gate blocker.
