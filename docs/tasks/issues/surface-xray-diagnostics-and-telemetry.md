---
title: Surface Xray diagnostics and telemetry
type: task
status: blocked
area: outbound
priority: medium
owner: unassigned
parent: epic-xray-provider-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-06-05
---

## Summary

Expose Xray provider state in Home, Diagnostics, exports, and service telemetry.

## Motivation

The app should make Xray failures diagnosable without turning user profiles or live endpoints into logs. Existing diagnostics already distinguish native proxy, relay, WARP, and tunnel state; Xray needs the same typed treatment.

## Scope

- In scope: runtime snapshot fields, Xray version, readiness, listener state, outbound health, config-validation errors, ping/stat probes where safe, and redacted export summaries.
- Out of scope: full packet capture of tunneled traffic and endpoint disclosure in logs or task notes.

## Acceptance criteria

- [x] Home connection stages identify Xray provider readiness and provider failures distinctly from tunnel failures. — `XrayConnectionStage` (Validating → StartingEngine → ListenerReady → ProbingOutbound → Connected, with a `ProviderFailed` branch) plus `XrayProviderFailureClass` (protect-loop / DNS-loop separated from a dead server); `fromSnapshot`/`canTransition` derivation covered by `XrayProviderDiagnosticsTest`. NOTE: the staging/failure-class *derivation* is verified offline; the actual Home Compose rendering of these stages is `:app` and not exercised here.
- [ ] Diagnostics can run a provider-path check through the active Xray mode. — typed `XrayProviderProbeKind` / `ProbeResult` / `ProbeReport` (Version / WrapperPing / ListenerReadiness safe in-process; StatApi flagged child-process-only) landed and unit-tested, but live probe population (libXray `Ping`/`XrayVersion`, listener readiness) lives in `:core:service` and is device/gomobile-verified. OPEN: live provider-path run blocked on gomobile/libXray + device.
- [x] Export/share summaries redact profile credentials and live endpoints. — `XrayProviderTelemetrySummaries` routes every endpoint/secret through `XrayProfileRedactor`; verified by `XrayProviderDiagnosticsTest` (offline).
- [x] Xray API/stat probing is used only when enabled safely for the Android runtime topology. — `StatApi` probe kind is typed and flagged child-process-only (never in-process for the Android TUN topology); the safe set is Version/WrapperPing/ListenerReadiness. Type-level gate verified offline; the in-process-safety claim itself is enforced by the `:core:service` runtime and is device-verified, not run here.
- [x] Regression fixtures cover provider healthy, config invalid, protect failure, DNS-loop suspected, and outbound unreachable states. — `XrayProviderDiagnosticsFixtures` (all five states) asserted by `XrayProviderDiagnosticsTest` (15 tests green offline).

## Progress

**2026-05-30** — Diagnostics/telemetry data substrate landed (commit `feat(xray): surface typed Xray provider diagnostics and redacted telemetry`) in `:core:data:runtime-state`: `XrayProviderSnapshot`, the typed readiness/listener/outbound/failure-class axes, `XrayConnectionStage` Home staging, the user-triggered probe-kind/report types, redacted telemetry summaries, and the five-state regression fixtures — 15 unit tests green offline. Remaining (blocked on toolchain/hardware): live snapshot population (libXray `Ping`/`XrayVersion`, real listener readiness, stat API) lands in `:core:service` and the Home/Diagnostics Compose surfaces in `:app`; both are device/gomobile-verified and were not run on the offline toolchain.

## Design notes

If Xray metrics/API mode is not safe in-process, prefer wrapper `Ping`, `XrayVersion`, listener readiness, and existing tunnel telemetry for the first build.

## Risks / open questions

- Provider diagnostics can accidentally become a reachability scanner. Keep it user-triggered and tied to the active profile.

## Links

- [[Epic - Xray provider mode]]
- [[Run Xray as managed VPN relay runtime]]
- ripdpi-android-xray-provider-plan-2026-04-24

## Work log

- 2026-06-05: Data substrate fully landed in `core/data/runtime-state` (XrayProviderSnapshot, XrayConnectionStage, XrayProviderFailureClass, XrayProviderProbeKind/ProbeResult/ProbeReport, XrayProviderTelemetrySummaries, XrayProviderDiagnosticsFixtures — 15 unit tests); criteria 1/3/4/5 verified. Criterion 2 (live probe population via libXray/gomobile in `:core:service`) and Home/Diagnostics Compose surfaces (`:app`) are absent from source — blocked on gomobile/libXray packaging task.
- 2026-06-11 (offline re-verify): `:core:data:runtime-state:testDebugUnitTest` green — `XrayProviderDiagnosticsTest` (15), 0 failures. Criteria 1/3/4/5 stay code-complete (typed staging, redacted summaries, in-process-safe probe gate, five-state fixtures); criterion 2 (live probe population in `:core:service` + Home/Diagnostics Compose) stays OPEN on the AAR/device gate — see `docs/native/libxray-unblock-checklist.md`. Status stays `blocked`.
