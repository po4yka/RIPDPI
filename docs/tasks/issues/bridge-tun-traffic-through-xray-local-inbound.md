---
id: OUT-1786264762917422
title: Bridge TUN traffic through Xray local inbound
kind: feature
status: review
area: outbound
priority: high
owner: unassigned
parent: EPC-1786264762917329
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917422-bridge-tun-traffic-through-xray-local-inbound
created: 2026-04-24
updated: 2026-08-28
status_detail: Exact source 9b18e5122 passed local APK/emulator acceptance and hosted CI 33199013272; protected PR455 integration as baeaf98ca is verified.
---

## Summary

Route Android VPN TUN traffic through Xray's local inbound for the first Xray tunneled outbound profile milestone.

## Motivation

RIPDPI already has a well-tested TUN-to-SOCKS path with DNS interception, handover handling, and telemetry. Using Xray as the local inbound preserves that path while adding Xray outbound support.

## Scope

- In scope: local Xray SOCKS/HTTP inbound selection, tunnel config handoff, auth/localhost hardening, DNS-loop avoidance, handover restart behavior, and traffic-smoke validation.
- Out of scope: shipping direct `libXray.SetTunFd` until lifecycle and telemetry parity are proven.

## Acceptance criteria

- [x] VPN startup can select Xray as the tunnel's upstream local endpoint. — `XrayTunnelHandoff` resolves the upstream from `VpnProviderKind` (Native keeps tun2socks; Xray points the tunnel at `127.0.0.1:localInboundPort`); covered by `XrayTunnelHandoffTest` and `XrayProviderOrchestratorTest`.
- [x] Xray outbound sockets and DNS are protected so provider traffic does not loop into the TUN fd. — protect-first ordering in `RipDpiXrayRuntime`; DNS ownership pinned to the tunnel; proven by `XrayProtectFdContractTest` and `XrayDnsLoopRegressionTest`.
- [x] Existing tunnel telemetry remains available when the upstream endpoint is Xray instead of RIPDPI-native proxy. — `XrayProviderOrchestrator` drives the `ManagedTunnel` seam unchanged; orchestrator tests assert the tunnel lifecycle is preserved when upstream is Xray.
- [x] Network handover restarts both Xray and tunnel when the local inbound or provider route changes. — route-change dual-restart (tunnel stopped before Xray) covered by `XrayProviderOrchestratorTest` / `XrayServiceLifecycleMatrixTest`.
- [ ] A local/device smoke test proves traffic exits through the Xray outbound. — documented in `docs/contributor/xray-tun-bridge-smoke.md` but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK29 native engine + device + live server; the smoke lane cannot run on the offline toolchain.

## Progress

**2026-05-30** — TUN-to-Xray bridge orchestration landed (commit `feat(xray): bridge VPN TUN through protected Xray local inbound`): `XrayTunnelHandoff` + `XrayProviderOrchestrator` in `:core:engine-api` let VPN startup point the existing tunnel at Xray's loopback inbound while keeping native the default, with protect-first outbound protection, tunnel-owned DNS (split `XrayDns` rejected), loopback-only inbound hardening, and dual-restart on handover. The `LibXraySetTunFd` topology stays a declared-but-unimplemented fail-fast branch. Verified offline: 19 new tests green in `:core:engine-api`, detekt clean. Remaining: the device/emulator smoke that proves real egress through the Xray outbound — blocked on gomobile/libXray + NDK29 native + device + server.

## Design notes

Keep the direct `SetTunFd` path as an explicit follow-up decision, not an accidental first implementation.

## Risks / open questions

- Xray local inbound authentication support must be validated before exposing any localhost listener beyond the tunnel's private use.
- DNS interception ownership needs one clear source of truth: RIPDPI tunnel, Xray DNS, or a deliberately split model.

## Work log

- 2026-06-05: All 4 orchestration criteria verified in source (XrayTunnelHandoff, XrayProviderOrchestrator, XrayProtectFdContractTest, XrayDnsLoopRegressionTest, XrayProviderOrchestratorTest, XrayServiceLifecycleMatrixTest all exist in core/engine-api); smoke criterion remains open — docs/contributor/xray-tun-bridge-smoke.md documents the manual lane but CI cannot run it without gomobile/libXray + NDK29 + live server.
- 2026-06-05: Re-audit confirms source evidence for criteria 1–4: XrayTunnelHandoff.kt (152 lines) and XrayProviderOrchestrator.kt (263 lines) in core/engine-api/src/main; protect-first ordering confirmed in RipDpiXrayRuntime.kt (line 83–84: bridge.registerProtect before Xray opens sockets); all 5 test files present in core/engine-api/src/test. Status changed from `blocked` to `doing` — `blocked_by` was empty, the constraint is a CI/device gap, not a sibling-task dependency; `doing` matches 4/5 criteria verified and one remaining.
- 2026-06-11 (offline re-verify): `:core:engine-api:testDebugUnitTest` green — `XrayTunnelHandoffTest` (6), `XrayProviderOrchestratorTest` (13), `XrayDnsLoopRegressionTest` (5), `XrayProtectFdContractTest` (4), 0 failures. Criteria 1–4 stay code-complete offline; criterion 5 (device egress smoke) stays OPEN on the gomobile/libXray + NDK29 + device + live-server gate — see `docs/native/libxray-unblock-checklist.md`. Status stays `doing`.

## Links

- [[Epic - Xray provider mode]]
- [[Run Xray as managed VPN relay runtime]]
- ripdpi-android-xray-provider-plan-2026-04-24
