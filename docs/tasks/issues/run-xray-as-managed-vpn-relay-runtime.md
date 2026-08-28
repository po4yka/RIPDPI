---
id: OUT-1786264762917107
title: Run Xray as managed VPN relay runtime
kind: feature
status: review
area: outbound
priority: high
owner: codex
parent: EPC-1786264762917329
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917107-run-xray-as-managed-vpn-relay-runtime
created: 2026-04-24
updated: 2026-08-28
status_detail: Exact source 9b18e5122 passed local APK/emulator acceptance and hosted CI 33199013272; protected PR455 integration as baeaf98ca is verified.
---

## Summary

Implement a supervised Xray runtime that starts, reports readiness, exposes health, and stops cleanly inside RIPDPI's Android service layer.

## Motivation

Xray must behave like the existing managed proxy/relay runtimes: no ambiguous "running" state before listeners bind, no silent crashes, no leaked native resources, and no recursive VPN socket loops.

## Scope

- In scope: `RunXrayFromJSON` startup, `StopXray` shutdown, protect-controller registration, DNS initialization, readiness probing, state mapping, telemetry snapshots, and supervisor exit causes.
- Out of scope: UI profile editing and non-Xray providers.

## Ownership

- Root writer, `codex/xray-managed-runtime-20260828`: engine-api runtime/bridge contracts, linked Kotlin bridge, service lifecycle and their tests; profile snapshots, Gradle integration, Android runtime smoke, task/specification files.
- Native build writer, `codex/xray-managed-native-20260828`: `scripts/native/*libxray*`, `native/xray/patches/`, and focused native packaging/protection tests under `scripts/tests/`. Ownership was subsequently extended to `.github/actions/build-xray/`, `ci.yml`, `release-candidate.yml` and their Python contract tests. Separate worktree; no Kotlin, shared version catalog, lockfile, or task state edits.
- Read-only architecture and final review agents own no files. Root serializes all integration and shared-file changes. No new production dependencies or pin changes are planned.

## Acceptance criteria

- [x] Real linked libXray registers one replaceable protection callback before startup and DNS setup; a denied callback aborts the Go socket operation, including Xray system dialer/listener paths.
- [x] Startup verifies the configured local SOCKS listener before TUN handoff. Start failure/cancellation retains native ownership until cleanup is confirmed.
- [x] Stop has a bounded caller wait, typed outcomes, idempotency, and retained ownership while native cleanup is pending or failed. Late completion cannot release a newer session.
- [x] Version and lifecycle telemetry are secret-free. A ready local listener is not reported as verified outbound reachability. Unexpected runtime exit stops the owning VPN session.
- [x] Regression tests cover invalid config, denied protection, readiness, cancellation, hung/failed/late stop, and exit mapping. A real linked Android runtime exchanges traffic with a controlled loopback peer, stops and restarts successfully; shipping builds include the verified AAR.

## Current evidence boundary

The historical fake-only checks below do not establish native acceptance. On 2026-08-28 all five execution steps were reopened after source review found ignored protection denial, swallowed native stop errors, an unbounded blocking stop, and missing post-readiness exit handling. The service consumer now exists; older statements that it is missing are historical. Required evidence is a built, verified AAR plus real linked Android emulator execution and exact-SHA CI. Physical-device/VPS deployment is not part of this task.

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

- 2026-08-28: Replaced fake-only acceptance with actual pinned patched libXray. Native protection denial now aborts dial/listen/DNS; partial construction/start and failed Close retain the owner. A process-owned worker bounds caller waits without cancelling Go calls; service destruction permanently revokes late callbacks, and failed cleanup retains the TUN barrier. Endpoint-only underlay bootstrap preserves SNI/Host and profile DNS policy. Native, linked bridge, lifecycle and stale-exit regressions pass. Engine-api 61, engine 311 and service 1894 unit tests pass without skips. API34 AndroidTestOrchestrator runs both actual VLESS loopback tests successfully (14.082s): payload, stop/restart, denied protection and invalid identity. The four-ABI AAR passes provenance/API/16 KiB ELF checks. Exact-SHA hosted CI remains a separate required acceptance gate; no phone/VPS deployment is claimed.
- 2026-08-28: Corrected CI bootstrap after upstream `gomobile init` overwrote pinned gobind with latest. On pushed `6e4bfccdd`, Linux native producer and linked Kotlin jobs pass. Its actual four-ABI AAR passes independent verification and runs in the final API34 APK: 2/2 real runtime tests pass in 11.592s. Completed CI has 37 passing jobs, including API27/33/35 Android tests and all GitHub/F-Droid/Play debug and release checks. Overall CI remains blocked by unchanged Rust hotspot/Clone guards, DNS assertions and a port collision in an unchanged Rust fixture test; no task closure or gate waiver is claimed.
