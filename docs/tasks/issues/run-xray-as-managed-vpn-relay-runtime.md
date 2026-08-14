---
id: OUT-1786264762917107
title: Run Xray as managed VPN relay runtime
kind: feature
status: blocked
area: outbound
priority: high
owner: unassigned
parent: EPC-1786264762917329
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917107-run-xray-as-managed-vpn-relay-runtime
created: 2026-04-24
updated: 2026-07-26
status_detail: externally-gated — real gomobile-backed bridge and Android device execution remain unavailable
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

> All five criteria are verified against `FakeXrayNativeBridge`, which replays the full observable native contract. The criteria are met at the adapter/contract level. The real gomobile-backed `XrayNativeBridgeLibXrayImpl` now COMPILES and LINKS against the per-ABI libXray AAR (`:core:engine`, `src/xrayLinked`) and is bound as the `XrayNativeBridge` singleton (`XrayBridgeModule`, `@Provides @XrayDatDir`); its pure logic — base64 `CallResponse` parsing, `version()` `data` extraction, the `DialerController` protect adapter, protect-first ordering, and the request-builder throw path — is unit-verified OFFLINE via the `LibXrayFfi` seam (`XrayNativeBridgeLibXrayImplTest`, no gojni load). What has NOT executed is the real native `RunXrayFromJSON`/`StopXray`/`getXrayState` against a live Xray process. The sole remaining gates are (1) device + live-server egress smoke (B-3) and (2) the missing service-integration consumer — no `:core:service` code injects `XrayNativeBridge` yet, so the binding is ready-to-inject but not yet constructed in production. Frontmatter `status` reflects the device-smoke-pending state.

## Progress

**2026-05-30** — Managed runtime adapter landed (commit `feat(xray): managed Xray runtime adapter with typed lifecycle`): `RipDpiXrayRuntime` in `:core:engine-api` maps libXray onto the existing `start/awaitReady/stop/pollTelemetry` managed-runtime contract, with protect-first ordering, bounded idempotent stop, typed lifecycle/stop causes, and secret-free telemetry — all driven by `FakeXrayNativeBridge` and covered by 14 offline-green unit tests. The real `XrayNativeBridgeLibXrayImpl` is the single libXray seam and is UNVERIFIED IN CI (throws until compiled in the libXray-linking module; gomobile AAR absent). Remaining: run the adapter against a real libXray bridge on device — blocked on the gomobile build, NDK29 native link, and a live server.

## Design notes

Map Xray readiness and stop outcomes into the same service-level language used for proxy, relay, WARP, and tunnel runtimes.

## Risks / open questions

- libXray wrapper calls may be process-global; the app should assume only one active Xray instance until proven otherwise.
- Metrics/API mode may require a child process according to upstream notes; do not rely on it until tested on Android.

## Links

- [[Epic - Xray provider mode]]
- Package libXray for Android ABIs — completed task; see git history
- Render validated Xray client configs — closed task (renderer/validation/redactor shipped; git history is the audit trail)
- ripdpi-android-xray-provider-plan-2026-04-24

## Work log

- 2026-06-05: Adapter layer complete — `RipDpiXrayRuntime` + 14 tests in `RipDpiXrayRuntimeTest` + 4 tests in `XrayProtectFdContractTest` all green offline via `FakeXrayNativeBridge`; `XrayNativeBridgeLibXrayImpl` throws `NotImplementedError` on every method (UNVERIFIED IN CI, gomobile libXray AAR absent). Blocked on gomobile build + NDK29 native link + real device run.
- 2026-06-05: Audit confirmed — all 5 [x] criteria verified against source: protect-first registration at `RipDpiXrayRuntime.kt:83-84`, bounded idempotent stop with `StopCause` sealed interface at lines 150-185/274-284, `pollTelemetry()` with `NativeRuntimeSnapshot` at lines 199-206, 14 tests in `RipDpiXrayRuntimeTest` covering startup failure/invalid config/late stop/crash mapping (function names confirmed). `XrayNativeBridgeLibXrayImpl` confirmed to throw `NotImplementedError` on all 6 methods (lines 47-80). Status remains `blocked` — real end-to-end path blocked on gomobile libXray AAR; `blocked_by: []` reflects no tracked sibling task for the blocker. Note inside criteria block says "backlog" but frontmatter `blocked` is correct; no change to status.
- 2026-06-11 (offline re-verify): `:core:engine-api:testDebugUnitTest` green — `RipDpiXrayRuntimeTest` (14) + `XrayProtectFdContractTest` (4), 0 failures, against `FakeXrayNativeBridge`. All 5 criteria stay code-complete at the adapter/contract level; real `RunXrayFromJSON`/`StopXray`/`XrayVersion` still unexecuted (AAR gate). See `docs/native/libxray-unblock-checklist.md`. Status stays `blocked`.
- 2026-06-15 (A-3 bridge link + bind): The throwing `XrayNativeBridgeLibXrayImpl` was removed from `:core:engine-api` and re-implemented as the real gomobile-backed bridge in `:core:engine` `src/xrayLinked` (FFI seam `LibXrayFfi` / `GomobileLibXrayFfi` over `libXray.LibXray.*`; the only gojni-loading code), with a parity `src/xrayStub` for offline builds — exactly one variant is added to `main`. `XrayBridgeModule` binds it as the `XrayNativeBridge` singleton (`@Provides`, `@XrayDatDir` from `resolveGeoDatabasePaths`). Corrected the native contract vs the old stub comments: `CallResponse` error key is `error` (not `err`); `runXrayFromJSON`/`stopXray`/`xrayVersion` all return a base64-wrapped `CallResponse` (`version` reads `data`); flow is `newXrayRunFromJSONRequest(datDir, mph, json)` → base64 req → `runXrayFromJSON(req)`. New gated offline tests (`XrayNativeBridgeLibXrayImplTest`, `src/testXrayLinked`) cover parsing + protect adapter + ordering via a `FakeLibXrayFfi` (never classloads `LibXray`). Known device gaps for B-3: the Go wrapper discards `protectFd`'s boolean (denial can't abort the socket natively); `GeoDatabasePaths` names files `.db` while xray-core expects `.dat` (datDir/filename reconciliation); no `:core:service` consumer injects the binding yet. Status → `device-smoke-pending`.
