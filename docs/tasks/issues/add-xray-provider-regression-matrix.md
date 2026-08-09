---
id: OUT-1786264762917829
title: Add Xray provider regression matrix
kind: feature
status: dropped
area: outbound
priority: medium
owner: unassigned
parent: EPC-1786264762917329
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917829-add-xray-provider-regression-matrix
created: 2026-04-24
updated: 2026-08-09
closed_at: "2026-08-09T11:12:18Z"
closed_reason: superseded by the active Xray diversification task
evidence_summary: Completed implementation remains in Git; all remaining deliverables are consolidated under OUT-1786264762917422.
---

## Summary

Add focused automated coverage for the first Xray provider integration.

## Context

The risky parts are lifecycle, config rendering, socket protection, DNS loops, provider telemetry, and Android VPN handoff. Tests should lock those down before Xray mode becomes a default or recommended fallback.

## Acceptance criteria

- [x] Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and redaction. — `XrayConfigRendererTest`, `XrayProfileRedactorTest`, `XrayRedactionRegressionTest` (`:core:data:catalog`, green offline).
- [x] Service tests cover Xray startup failure, readiness timeout, stop, restart, and handover behavior. — `XrayServiceLifecycleMatrixTest` (one named test per edge) + `RipDpiXrayRuntimeTest` (`:core:engine-api`, green offline).
- [x] Protect-fd tests prove Xray dialer/listener sockets use the Android VPN protection path. — `XrayProtectFdContractTest`: a socket-simulating fake bridge asserts protect strictly precedes connect, a denied protect aborts the socket, and the loopback inbound is never offered to protect (green offline).
- [x] DNS-loop regression proves provider bootstrap DNS does not re-enter TUN. — `XrayDnsLoopRegressionTest`: DNS ownership pinned to the tunnel, split `XrayDns` not constructible for the bridged topology, `SetTunFd` topology refused (green offline).
- [ ] Device/emulator smoke test verifies active VPN traffic exits through the Xray outbound path. — documented in `docs/contributor/xray-tun-bridge-smoke.md` / `xray-regression-matrix.md` but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK29 native + device/emulator + live server.
- [x] CI or documented manual lanes identify which Xray tests need network, emulator, or private fixture dependencies. — `docs/contributor/xray-regression-matrix.md` indexes the whole surface and splits CI-offline lanes from device/emulator, live-network, and private-fixture lanes, with a promotion checklist.

## Progress

**2026-05-30** — Offline regression matrix landed (commit `test(xray): add Xray provider regression matrix and document device/network lanes`): config golden + redaction tests, the service lifecycle matrix, the protect-fd contract test, and the DNS-loop regression are all green offline across `:core:data:catalog` and `:core:engine-api` (the latter de-flaked to pass 4 back-to-back `--rerun-tasks` runs). `docs/contributor/xray-regression-matrix.md` indexes every lane and isolates the CI-offline set from the device/network/private-fixture set. Remaining: the one device/emulator smoke that proves real egress through the Xray outbound — blocked on gomobile/libXray + NDK29 native + device + server.

## Notes

Keep private endpoints out of fixtures. Use local synthetic fixtures or operator-provided private test profiles outside the vault.

## Links

- [[Epic - Xray provider mode]]
- [[Bridge TUN traffic through Xray local inbound]]
- [[Surface Xray diagnostics and telemetry]]
- ripdpi-android-xray-provider-plan-2026-04-24

## Work log

- 2026-06-05: 5 of 6 acceptance criteria verifiably met in main (XrayConfigRendererTest, XrayProfileRedactorTest, XrayRedactionRegressionTest in core/data/catalog; XrayServiceLifecycleMatrixTest, RipDpiXrayRuntimeTest, XrayProtectFdContractTest, XrayDnsLoopRegressionTest in core/engine-api; docs/contributor/xray-regression-matrix.md present). One criterion remains open: device/emulator TUN-bridge smoke test, blocked on gomobile/libXray + NDK29 + real device/server.
- 2026-06-05: Re-audit confirmed — all 5 offline test files exist at the cited paths; test content verified (protect-before-connect assertion, loopback exclusion, SetTunFd refusal, split-XrayDns construction guard all present). docs/contributor/xray-regression-matrix.md explicitly separates CI-offline lanes from device/emulator/live-network/private-fixture lanes with a promotion checklist. Status remains blocked: criterion 5 (TUN-bridge egress smoke) has a real infrastructure blocker (no gomobile/libXray + NDK29 in CI); blocked_by is empty because the blocker is infrastructure, not a sibling task.
- 2026-06-11 (offline re-verify): offline matrix re-run green — `:core:data:catalog` (Renderer 7, Redactor 4, RedactionRegression 5, Validator 6) + `:core:engine-api` (ServiceLifecycleMatrix 6, RipDpiXrayRuntime 14, ProtectFdContract 4, DnsLoopRegression 5), 0 failures. Criteria 1–4 + 6 stay code-complete; criterion 5 (device/emulator egress smoke) stays OPEN on the gomobile/libXray + NDK29 + device + server gate — see `docs/native/libxray-unblock-checklist.md`. Status stays `doing`.
