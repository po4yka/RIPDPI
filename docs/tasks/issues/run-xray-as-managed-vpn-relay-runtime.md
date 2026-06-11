---
title: Run Xray as managed VPN relay runtime
type: task
status: blocked
area: outbound
priority: high
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-06-10
---

## Summary

Implement a supervised Xray runtime that starts, reports readiness, exposes health, and stops cleanly inside RIPDPI's Android service layer.

## Motivation

Xray must behave like the existing managed proxy/relay runtimes: no ambiguous "running" state before listeners bind, no silent crashes, no leaked native resources, and no recursive VPN socket loops.

## Scope

- In scope: `RunXrayFromJSON` startup, `StopXray` shutdown, protect-controller registration, DNS initialization, readiness probing, state mapping, telemetry snapshots, and supervisor exit causes.
- Out of scope: UI profile editing and non-Xray providers.

## Acceptance criteria

- [x] Runtime registers libXray dialer/listener protection before starting Xray. — `RipDpiXrayRuntime` registers the protect controller with the bridge BEFORE start; protect-first ordering is asserted by `RipDpiXrayRuntimeTest` and `XrayProtectFdContractTest`.
- [x] Startup waits for a concrete listener or verified Xray state before VPN tunnel handoff. — readiness success/timeout covered in `RipDpiXrayRuntimeTest`.
- [x] Stop path is bounded, idempotent, and reports typed clean/failed stop causes. — typed `StopCause` (Clean/AlreadyStopped/Failed), bounded via IO dispatcher; idempotent/late/hung-stop tests green.
- [x] Xray version and basic provider state flow into service telemetry without exposing profile secrets. — `pollTelemetry()` emits a `NativeRuntimeSnapshot` with version+state and a secret-free assertion test.
- [x] Unit or service tests cover startup failure, invalid config, late stop, and crash/exit mapping. — 14 tests in `RipDpiXrayRuntimeTest` (green offline in `:core:engine-api`).

> All five criteria are verified against `FakeXrayNativeBridge`, which replays the full observable native contract. The criteria are met at the adapter/contract level. The real gomobile-backed `XrayNativeBridgeLibXrayImpl` (`RunXrayFromJSON` / `StopXray` / `XrayVersion`) has NOT executed here — the libXray AAR is absent from the offline toolchain — so end-to-end behavior against a real Xray process REMAINS OPEN (blocked on gomobile/libXray build, not on missing code). That gomobile/libXray build is the sole remaining gate; frontmatter `status: blocked` reflects it.

## Progress

**2026-05-30** — Managed runtime adapter landed (commit `feat(xray): managed Xray runtime adapter with typed lifecycle`): `RipDpiXrayRuntime` in `:core:engine-api` maps libXray onto the existing `start/awaitReady/stop/pollTelemetry` managed-runtime contract, with protect-first ordering, bounded idempotent stop, typed lifecycle/stop causes, and secret-free telemetry — all driven by `FakeXrayNativeBridge` and covered by 14 offline-green unit tests. The real `XrayNativeBridgeLibXrayImpl` is the single libXray seam and is UNVERIFIED IN CI (throws until compiled in the libXray-linking module; gomobile AAR absent). Remaining: run the adapter against a real libXray bridge on device — blocked on the gomobile build, NDK29 native link, and a live server.

## Design notes

Map Xray readiness and stop outcomes into the same service-level language used for proxy, relay, WARP, and tunnel runtimes.

## Risks / open questions

- libXray wrapper calls may be process-global; the app should assume only one active Xray instance until proven otherwise.
- Metrics/API mode may require a child process according to upstream notes; do not rely on it until tested on Android.

## Links

- [[Epic - Xray provider mode]]
- [[Package libXray for Android ABIs]]
- Render validated Xray client configs — closed task (renderer/validation/redactor shipped; git history is the audit trail)
- ripdpi-android-xray-provider-plan-2026-04-24

## Work log

- 2026-06-05: Adapter layer complete — `RipDpiXrayRuntime` + 14 tests in `RipDpiXrayRuntimeTest` + 4 tests in `XrayProtectFdContractTest` all green offline via `FakeXrayNativeBridge`; `XrayNativeBridgeLibXrayImpl` throws `NotImplementedError` on every method (UNVERIFIED IN CI, gomobile libXray AAR absent). Blocked on gomobile build + NDK29 native link + real device run.
- 2026-06-05: Audit confirmed — all 5 [x] criteria verified against source: protect-first registration at `RipDpiXrayRuntime.kt:83-84`, bounded idempotent stop with `StopCause` sealed interface at lines 150-185/274-284, `pollTelemetry()` with `NativeRuntimeSnapshot` at lines 199-206, 14 tests in `RipDpiXrayRuntimeTest` covering startup failure/invalid config/late stop/crash mapping (function names confirmed). `XrayNativeBridgeLibXrayImpl` confirmed to throw `NotImplementedError` on all 6 methods (lines 47-80). Status remains `blocked` — real end-to-end path blocked on gomobile libXray AAR; `blocked_by: []` reflects no tracked sibling task for the blocker. Note inside criteria block says "backlog" but frontmatter `blocked` is correct; no change to status.
- 2026-06-11 (offline re-verify): `:core:engine-api:testDebugUnitTest` green — `RipDpiXrayRuntimeTest` (14) + `XrayProtectFdContractTest` (4), 0 failures, against `FakeXrayNativeBridge`. All 5 criteria stay code-complete at the adapter/contract level; real `RunXrayFromJSON`/`StopXray`/`XrayVersion` still unexecuted (AAR gate). See `docs/native/libxray-unblock-checklist.md`. Status stays `blocked`.
