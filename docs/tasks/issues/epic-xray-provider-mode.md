---
title: Epic - Xray provider mode
type: epic
status: backlog
area: outbound
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Epic - Xray provider mode #repo/RIPDPI #area/outbound #status/backlog ⏫

## Goal

Add a first remote VPN-client provider mode to RIPDPI by embedding `xray-core` through `libXray`, with VLESS/REALITY and XHTTP as the initial profile targets.

## Why now

Direct-mode now has enough product framing to be honest when it cannot solve a network locally. The next practical fallback is a managed relay-provider path inside the same Android VPN UX, and Xray/libXray is the first provider the user wants to support.

## Key decisions

- **Provider mode, not direct-mode replacement.** Xray-backed tunneled outbound profile mode is a separate remote-relay provider that can be selected when direct-mode is unsuitable.
- **Start with libXray.** Do not reimplement Xray protocol behavior in RIPDPI-native Rust for the first milestone; wrap the upstream library and isolate its unstable API behind a local adapter.
- **Protect sockets before startup.** Xray sockets and DNS lookups must call Android `VpnService.protect(fd)` so the provider does not route itself back into the TUN device.
- **Conservative tunnel path first.** Prefer the existing TUN-to-local-inbound routing path for the first internal build, while evaluating direct `SetTunFd` only after lifecycle and telemetry parity is proven.
- **Secret-safe diagnostics.** Profile import, runtime errors, and diagnostic exports must redact UUIDs, private keys, server addresses, and live endpoints.

## Scope

- **In scope:** libXray packaging, provider architecture, Xray JSON profile rendering/validation, managed Xray runtime lifecycle, Android socket protection, VPN tunnel routing through Xray, profile UX, telemetry, diagnostics, and regression coverage.
- **Out of scope:** non-Xray provider SDKs, server provisioning automation, paid subscription/payment flows, and replacing the existing direct-mode native engine.

## Ship definition

- [ ] RIPDPI can start Android VPN mode with Xray selected as the active provider.
- [ ] At least VLESS/REALITY and XHTTP profile shapes validate and render to Xray JSON without leaking secrets.
- [ ] Xray sockets are protected from the VPN loop, including DNS and listener paths.
- [ ] Home, Diagnostics, and Settings show typed Xray provider state.
- [ ] Lifecycle, config, protect-fd, telemetry, and smoke tests cover the first internal build.

## Child tasks

**Architecture**
- Define Xray VPN provider architecture (closed task)
- [[Package libXray for Android ABIs]]

**Runtime path**
- [[Render validated Xray client configs]]
- [[Run Xray as managed VPN relay runtime]]
- [[Bridge TUN traffic through Xray local inbound]]

**Product and proof**
- [[Add Xray profile UX and import flow]]
- [[Surface Xray diagnostics and telemetry]]
- [[Add Xray provider regression matrix]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Dependencies

- Depends on: Recurring upstream watch for xray-core REALITY ECH XHTTP changes (closed task) for version/deprecation tracking.
- Coordinates with: [[Epic - Direct-mode diagnostic state machine]] because direct-mode negative verdicts should hand off to provider-mode suggestions without collapsing the two concepts.
- Feeds: future release-pipeline work once Xray provider assets affect APK size, notices, and signed builds.

## Risks / open questions

- `libXray` explicitly does not guarantee API stability, so the adapter must contain version-specific breakage.
- Xray-core's release cadence can break profile assumptions faster than RIPDPI's normal app release cadence.
- Direct `SetTunFd` may look simpler but could duplicate or weaken existing TUN telemetry, DNS interception, and shutdown behavior.
- Geo assets, MPH cache files, and logs can increase APK/storage footprint or expose sensitive configuration if not scoped carefully.

## Links

- [[ripdpi-android]]
- ripdpi-android-xray-provider-plan-2026-04-24
- vless-reality-stack-research-2026-04-22
- Recurring upstream watch for xray-core REALITY ECH XHTTP changes (closed task)
- Child issues: 8
