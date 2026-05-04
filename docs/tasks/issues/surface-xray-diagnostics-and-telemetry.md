---
title: Surface Xray diagnostics and telemetry
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-xray-vpn-client-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Surface Xray diagnostics and telemetry #repo/RIPDPI #area/outbound #status/backlog 🔼

## Summary

Expose Xray provider state in Home, Diagnostics, exports, and service
telemetry.

## Motivation

The app should make Xray failures diagnosable without turning user profiles or
live endpoints into logs. Existing diagnostics already distinguish native
proxy, relay, WARP, and tunnel state; Xray needs the same typed treatment.

## Scope

- In scope: runtime snapshot fields, Xray version, readiness, listener state,
outbound health, config-validation errors, ping/stat probes where safe, and
redacted export summaries.
- Out of scope: full packet capture of tunneled traffic and endpoint disclosure
in logs or task notes.

## Acceptance criteria

- [ ] Home connection stages identify Xray provider readiness and provider
    failures distinctly from tunnel failures.
- [ ] Diagnostics can run a provider-path check through the active Xray mode.
- [ ] Export/share summaries redact profile credentials and live endpoints.
- [ ] Xray API/stat probing is used only when enabled safely for the Android
    runtime topology.
- [ ] Regression fixtures cover provider healthy, config invalid, protect
    failure, DNS-loop suspected, and outbound unreachable states.

## Design notes

If Xray metrics/API mode is not safe in-process, prefer wrapper `Ping`,
`XrayVersion`, listener readiness, and existing tunnel telemetry for the first
build.

## Risks / open questions

- Provider diagnostics can accidentally become a reachability scanner. Keep it
user-triggered and tied to the active profile.

## Links

- [[Epic - Xray VPN client mode]]
- [[Run Xray as managed VPN relay runtime]]
- [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]
