---
title: Epic - Xray provider mode
type: epic
status: blocked
area: outbound
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-06-05
---

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

- [ ] RIPDPI can start Android VPN mode with Xray selected as the active provider. — OPEN: requires the real libXray bridge (`RunXrayFromJSON`) which needs the gomobile-built AAR + NDK29 native link + a device; none are present in the build environment, so a real Xray-backed VPN start has never run.
- [x] At least VLESS/REALITY and XHTTP profile shapes validate and render to Xray JSON without leaking secrets. — `XrayConfigRenderer` + `XrayConfigValidator` + `XrayProfileRedactor`, golden- and redaction-tested green offline.
- [ ] Xray sockets are protected from the VPN loop, including DNS and listener paths. — the protect-first ordering, DNS-loop avoidance, and protect-fd contract are test-proven offline against the runtime/bridge contract (`XrayProtectFdContractTest`, `XrayDnsLoopRegressionTest`), but the real socket protection of a running Xray is UNVERIFIED (needs the gomobile bridge + device).
- [ ] Home, Diagnostics, and Settings show typed Xray provider state. — the typed provider-state substrate (`XrayProviderSnapshot`, `XrayConnectionStage`, failure classes, redacted summaries) landed and is tested offline; the Home/Diagnostics/Settings Compose surfaces that render it live in `:app`/`:core:service` and are device/gomobile-verified, not run here.
- [ ] Lifecycle, config, protect-fd, telemetry, and smoke tests cover the first internal build. — lifecycle, config, protect-fd, DNS-loop, and telemetry tests are green offline; the device/emulator egress smoke remains OPEN (blocked on gomobile/libXray + NDK29 + device + server).

## Current status

**2026-05-30** — The full Kotlin/Gradle software substrate for Xray provider mode has landed across seven commits and is offline-test-verified where the toolchain allows. What is in the tree and proven by green offline tests: the **config renderer + validation gate + secret-safe redactor** (`:core:data:catalog`), the **managed Xray runtime adapter** mapping libXray onto the `start/awaitReady/stop/pollTelemetry` contract with protect-first ordering and typed lifecycle/stop causes (`:core:engine-api`, verified against a fake native bridge), the **TUN-to-Xray-local-inbound bridge orchestration** with tunnel-owned DNS and dual-restart handover (`:core:engine-api`), the **profile-selection + fail-closed import UX** with capability labels and 7-locale strings (`:app` + `:core:data` parsers), the **typed diagnostics/telemetry substrate** (snapshot, connection stages, failure classes, redacted summaries, regression fixtures) (`:core:data:runtime-state`), and the **offline regression matrix** (config golden, service lifecycle, protect-fd contract, DNS-loop). The libXray/xray-core **version pins, stable-vs-canary policy, license/NOTICE capture, gomobile build script, and artifact-verification gate** are also committed (no native binary committed).

Remaining blockers are all external toolchain/hardware, not missing code: (1) **gomobile libXray build** — Go + gomobile are absent, so no real per-ABI `.aar` exists and the real `RunXrayFromJSON`/`StopXray`/`Ping` bridge has never executed; (2) **NDK29 native link** — the environment ships NDK 28.2 only, so `:core:engine`/`:core:service`/`:app` native-linking builds and their device-verified surfaces (live telemetry population, Home rendering) could not run; (3) **device/emulator + live-server smoke** — the proof that real VPN traffic exits through the Xray outbound, and the `:app` UI test lane (also blocked by a pre-existing offline plugin-cache miss), require a device, a live Xray server, and the native artifacts above. The epic stays **not done**: its ship definition includes "RIPDPI can start Android VPN mode with Xray selected" and the device-traffic proof, neither of which is verifiable in this environment.

## Child tasks

Status as of 2026-05-30 (`done` = every acceptance criterion test-verified; `backlog` = code landed and offline-verified but ≥1 criterion blocked on external toolchain/hardware):

**Architecture**
- Define Xray VPN provider architecture (closed task)
- [[Package libXray for Android ABIs]] — backlog (pins/policy/license/verify-script landed; real gomobile ABI build OPEN)

**Runtime path**
- [[Render validated Xray client configs]] — **done** (renderer, validation gate, redactor, golden tests green offline)
- [[Run Xray as managed VPN relay runtime]] — backlog (adapter + lifecycle contract verified vs fake bridge; real libXray run OPEN)
- [[Bridge TUN traffic through Xray local inbound]] — backlog (orchestration verified offline; device egress smoke OPEN)

**Product and proof**
- [[Add Xray profile UX and import flow]] — backlog (parser/capability/mode-option verified offline; `:app` UI lane + onboarding OPEN)
- [[Surface Xray diagnostics and telemetry]] — backlog (typed substrate + fixtures verified offline; live Home/`:core:service` population OPEN)
- [[Add Xray provider regression matrix]] — backlog (config/lifecycle/protect-fd/DNS-loop suites green offline; device smoke OPEN)

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

## Work log

- 2026-06-05: Full Kotlin/Gradle substrate verified in source (XrayConfigRenderer, XrayNativeBridge interface, XrayNativeBridgeLibXrayImpl with throwUnlinked stubs, RipDpiXrayRuntime, XrayProviderOrchestrator, XrayProtectFdContractTest, XrayDnsLoopRegressionTest, XrayServiceLifecycleMatrixTest, XrayProviderSnapshot/XrayConnectionStage types, XrayProfileImportScreen UI). native/xray/ contains only README.md — no real .aar or .so. build-libxray.sh is marked UNVERIFIED IN CI. Three ship criteria remain open, all blocked on gomobile + Go toolchain (absent), NDK29 (environment has 28.2 only), and device/live-server smoke; status correctly remains blocked.
