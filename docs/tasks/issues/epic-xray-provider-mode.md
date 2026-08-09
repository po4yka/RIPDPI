---
id: EPC-1786264762917329
title: Epic - Xray provider mode
kind: epic
status: blocked
area: epic
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1786264762917329-epic-xray-provider-mode
created: 2026-04-24
updated: 2026-07-26
status_detail: externally-gated — real gomobile libXray execution, native link, and device egress proof remain unavailable
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
- [ ] Home, Diagnostics, and Settings show typed Xray provider state. — the typed provider-state substrate (`XrayProviderSnapshot`, `XrayConnectionStage`, failure classes, redacted summaries) AND the `:core:service` live-population backend now both landed and are CI-tested with fakes: `XrayProviderSnapshotDeriver` derives a secret-free snapshot from the live orchestrator state + bridge reads, threaded additively as `ServiceTelemetrySnapshot.xrayProviderSnapshot` on the existing telemetry loop; `XrayProviderDiagnosticsProbeRunner` + `XrayProviderSessionController.runProbes()` expose the user-triggered provider-path check (`:core:service:testDebugUnitTest` green). The `:app` Home/Diagnostics/Settings Compose surfaces that RENDER it now also landed and are CI + Roborazzi verified: `HomeXrayProviderBanner` (Home), `XrayProviderStatusCard` (Diagnostics, with the user-triggered probe via `DiagnosticsViewModel.runXrayProviderProbe()` → the process-`@Singleton` `XrayProviderProbeCoordinator` that the active session registers/clears), and `XrayProviderSettingsStatusRow` (Settings) — all provider-DISTINCT from tunnel failures (own `XrayProviderTone`/banner family; protect-loop & DNS-loop use `Restricted`, never the tunnel destructive `Error`), consuming the additive `ServiceTelemetrySnapshot.xrayProviderSnapshot`. New strings in all 8 locales; `XrayProviderStatusScreenshotTest` locks all five fixtures (light+dark); `:app:testGithubDebugUnitTest` (1262, 0) + `:app:lintGithubDebug` + `:app:assembleGithubDebug` (real `.so` links) green. STILL OPEN: live snapshots from a real running engine (device/gomobile-verified) — the only reason this checkbox stays `[ ]`.
- [ ] Lifecycle, config, protect-fd, telemetry, and smoke tests cover the first internal build. — lifecycle, config, protect-fd, DNS-loop, and telemetry tests are green offline; the device/emulator egress smoke remains OPEN (blocked on gomobile/libXray + NDK29 + device + server).

## Current status

**2026-05-30** — The full Kotlin/Gradle software substrate for Xray provider mode has landed across seven commits and is offline-test-verified where the toolchain allows. What is in the tree and proven by green offline tests: the **config renderer + validation gate + secret-safe redactor** (`:core:data:catalog`), the **managed Xray runtime adapter** mapping libXray onto the `start/awaitReady/stop/pollTelemetry` contract with protect-first ordering and typed lifecycle/stop causes (`:core:engine-api`, verified against a fake native bridge), the **TUN-to-Xray-local-inbound bridge orchestration** with tunnel-owned DNS and dual-restart handover (`:core:engine-api`), the **profile-selection + fail-closed import UX** with capability labels and 7-locale strings (`:app` + `:core:data` parsers), the **typed diagnostics/telemetry substrate** (snapshot, connection stages, failure classes, redacted summaries, regression fixtures) (`:core:data:runtime-state`), and the **offline regression matrix** (config golden, service lifecycle, protect-fd contract, DNS-loop). The libXray/xray-core **version pins, stable-vs-canary policy, license/NOTICE capture, gomobile build script, and artifact-verification gate** are also committed (no native binary committed).

Remaining blockers are all external toolchain/hardware, not missing code: (1) **gomobile libXray build** — Go + gomobile are absent, so no real per-ABI `.aar` exists and the real `RunXrayFromJSON`/`StopXray`/`Ping` bridge has never executed; (2) **NDK29 native link** — the environment ships NDK 28.2 only, so `:core:engine`/`:core:service`/`:app` native-linking builds and their device-verified surfaces (live telemetry population, Home rendering) could not run; (3) **device/emulator + live-server smoke** — the proof that real VPN traffic exits through the Xray outbound, and the `:app` UI test lane (also blocked by a pre-existing offline plugin-cache miss), require a device, a live Xray server, and the native artifacts above. The epic stays **not done**: its ship definition includes "RIPDPI can start Android VPN mode with Xray selected" and the device-traffic proof, neither of which is verifiable in this environment.

## Child tasks

Status as of 2026-05-30 (`done` = every acceptance criterion test-verified; `backlog` = code landed and offline-verified but ≥1 criterion blocked on external toolchain/hardware):

**Architecture**
- Define Xray VPN provider architecture (closed task)
- Package libXray for Android ABIs — completed task (packaging and verification contract landed; see git history). Real device execution remains an epic-level external proof gap.

**Runtime path**
- Render validated Xray client configs — **done**, closed task (renderer, validation gate, redactor, golden tests green offline; git history is the audit trail)
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
- 2026-06-05 (epic audit / child rollup): Re-verified against source. `core/data/catalog/src/main/kotlin/com/poyka/ripdpi/data/xray/XrayConfigRenderer.kt` exists with green offline render+redactor tests (`XrayConfigRendererTest`, `XrayProfileRedactorTest`, `XrayRedactionRegressionTest`) — ship criterion 2 stays [x]. `native/xray/` holds only README.md (no `.aar`/`.so`), so criteria 1/3/5 (device-/native-verified VPN start, live socket protection, egress smoke) stay [ ], blocked on absent gomobile/Go + NDK29 (env is 28.2) + device/live server. Child frontmatter rollup: package-libxray, run-runtime, surface-diagnostics, regression-matrix = blocked; bridge-tun-traffic, profile-ux = doing; the closed "Render validated Xray client configs" task is done (renderer source confirms). No child is fully done; none can complete without the external toolchain/hardware blocker. Status kept at `blocked` (the genuine external gate persists; the in-progress `doing` children are pre-link Kotlin work that cannot reach the device-verified ship criteria here).
- 2026-06-11 (triage + offline re-verify): Ran the offline lanes green — **121 Xray unit tests, 0 failures** across `:core:data:catalog` (Validator 6, Renderer 7, ImportParser 10, Redactor 4, RedactionRegression 5, Capability 4), `:core:data:runtime-state` (Diagnostics 15, ProviderKind 4, StateTransition 10, ModeOption 4, TunnelTopology 4), and `:core:engine-api` (RipDpiXrayRuntime 14, ProtectFdContract 4, DnsLoopRegression 5, ServiceLifecycleMatrix 6, ProviderOrchestrator 13, TunnelHandoff 6) via `testDebugUnitTest` (BUILD SUCCESSFUL). `native/xray/` still holds only README.md — no `.aar`/`.so` produced or fabricated. Ship criteria unchanged (1/3/4/5 stay [ ], 2 stays [x]); status stays `blocked`. Produced `docs/native/libxray-unblock-checklist.md` (ordered gomobile/NDK29 build steps + the missing CI workflow seam + per-child code-complete-vs-gated table). New finding surfaced during triage: the **profile-UX onboarding criterion (criterion 4) is a genuine code gap, not a toolchain gate** — no onboarding file references the Xray validator; it is the only open item closable offline without the AAR.
- 2026-06-15 (libXray AAR present → `:core:service` provider slice landed + CI-verified with fakes): With the real 49M libXray AAR now in `native/xray/artifacts/` (linkXray ON), the durable secret store + cross-module selection signal + `:core:service` session/telemetry backend all landed. New `:core:data:runtime-state` `XrayProviderStores.kt`: `DurableXrayProfileStore` splits a validated `XrayProfile` into a Keystore-encrypted secret half (`KeystoreEncryptedPreferences`, the same helper the relay/warp credential stores reuse — UUID + REALITY keys) and a plaintext metadata half (name/protocol/security/endpoint/inbound/DNS), re-joined on load; `XrayProviderSelectionStore` is the durable, secret-free cross-module signal (`providerKind` + `activeProfileId`) `:core:service` reads at VPN start. New `:core:service`: `VpnServiceXrayProtectController` (direct-JNI `VpnService.protect(int)`, same `VpnProtectFailureMonitor` as native, fail-closed), `XrayManagedTunnel` (Xray loopback inbound → `VpnTunnelRuntime`; Native is a guard error), `XrayProviderRouteBuilder` (render from the durable profile; Rejected → typed findings, never the secret config), `XrayProviderSnapshotDeriver`, `XrayProviderDiagnosticsProbeRunner`, `XrayProviderSessionController`; the orchestrator is instantiated `@ServiceSessionScope` in `VpnServiceSessionModule`; `VpnRuntimeCompositionCoordinator.startComposedRuntime` branches on the selection (Xray → orchestrator protect-first/ready-then-tunnel; Native BYTE-IDENTICAL); additive `ServiceTelemetrySnapshot.xrayProviderSnapshot` (no proto/schema bump) threaded on the existing telemetry loop. Verified offline: `:core:service:testDebugUnitTest` (1128, 0), `:core:data:runtime-state:testDebugUnitTest` (111, 0, incl. `DurableXrayProfileStoreTest` no-plaintext-leak), `:core:engine-api:testDebugUnitTest` (52, 0), `:core:data:catalog:testDebugUnitTest` (green), plus offline-stub compile of `:core:engine` with an empty AAR dir (no host-JVM real-bridge classload). Ship criteria stay 1/3/4/5 [ ] / 2 [x]: the device-verified items (real VPN start with Xray, real socket protection, live snapshots, egress smoke) and the `:app` Home/Diagnostics/Settings RENDER surface (separate slice) remain OPEN. Status stays `blocked`.
- 2026-06-15 (`:app` provider RENDER surface landed + CI/Roborazzi verified): The Home/Diagnostics/Settings Compose surfaces now render the typed Xray provider state, provider-DISTINCT from tunnel failures. New `:app`: `XrayProviderStatusPresentation` (stage/failure-class → tone + label; provider failures use `WarningBanner` Info/Warning/Restricted, protect-loop & DNS-loop = `Restricted`, NEVER the tunnel destructive `Error`), `XrayProviderStatusCard` (Diagnostics — stage + failure class + engine/listener/outbound + config findings + probe results), `HomeXrayProviderBanner` (Home, active-session only), `XrayProviderSettingsStatusRow` (Settings, compact read-only), `DiagnosticsXrayProviderController` (user-only probe trigger mirroring `DiagnosticsDpiToolsController.run*()`). Probe reaches the session via the NEW process-`@Singleton` `XrayProviderProbeCoordinator` in `:core:data:runtime-state` (registered by `XrayProviderSessionController` on start, cleared on stop; returns null when no Xray session is bound — `XrayProviderRouteBuilder` also moved off an ad-hoc `Json {}` to the shared `RipDpiEncodeDefaultsJson` to satisfy the JSON-centralization guard). Home reads `MainUiState.xrayProviderSnapshot`; Settings reads `SettingsViewModel.xrayProviderSnapshot`; both ← `ServiceTelemetrySnapshot.xrayProviderSnapshot`. 33 new strings landed in all 8 locales; `config/i18n/translatable-keys.txt` regenerated. Verified: `:app:testGithubDebugUnitTest` (1262, 0), `:core:service:testDebugUnitTest` + `:core:data:runtime-state:testDebugUnitTest` green, `:app:verifyRoborazziGithubDebug` for `XrayProviderStatusScreenshotTest` (10 NEW baselines = 5 fixtures × light/dark), `:app:lintGithubDebug` (MissingTranslation severity=error passes), `scripts/ci/check-translation-export.sh` in sync, and `:app:assembleGithubDebug` (real libXray `.so`/AAR links). STILL OPEN (device-pending): real Xray VPN egress + live snapshots from a running engine. Status stays `blocked` on the device gate.
- 2026-06-15 (import → durable-store WRITE wired — production live source): `:app` `DefaultXrayProfilePersistence` now persists the validated `XrayProfile` (sourced from the validated `XrayImportParser` gate, not a hand-rolled converter) to the Keystore-split `DurableXrayProfileStore` and flips the durable `XrayProviderSelectionStore` to Xray when XrayVpn is selected, so the `:core:service` session runner has a real production source; XrayVpn fails closed when no validated profile exists; native options clear the orphaned secret and stay byte-identical. Security + pr reviewed (no plaintext/secret leak; Keystore-split only). The Xray provider path is now wired END-TO-END except real device egress. Ship criteria unchanged on paper (1/3 [ ] need device-verified VPN start + socket protection; 4 typed-state rendering is CI/Roborazzi-verified pending only device confirmation of live snapshots; 5 egress smoke [ ]; 2 [x]). Status stays `blocked` on the device-egress gate.
