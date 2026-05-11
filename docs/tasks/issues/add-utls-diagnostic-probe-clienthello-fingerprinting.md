---
title: Wire uTLS Client into Diagnostic Probes for ClientHello Fingerprint Consistency
type: task
status: doing
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: [add-webhost-farm-dynamic-host-discovery, add-cidr-whitelist-detector]
blocked_by: []
created: 2026-05-10
updated: 2026-05-11
---

- [ ] #task Wire uTLS Client into Diagnostic Probes for ClientHello Fingerprint Consistency #repo/RIPDPI #area/diagnostics #status/doing 🔼

## Objective

Add `DiagnosticTlsClientFactory` that exposes the uTLS-equivalent transport-side TLS implementation to all diagnostic probes (`Tcp16FatHeaderProbe`, `WebhostFarm`, `DomainReachabilityScanner`, `RknLayeredProbePipeline`), guaranteeing diagnostic ClientHellos are fingerprint-identical to user-traffic ClientHellos.

## Context

dpi-ch uses `refraction-networking/utls` to emit a Chrome 120 ClientHello fingerprint for every diagnostic probe. The reasoning: a censor that fingerprints diagnostic tools' default Go/Python TLS stacks could selectively let those handshakes through, producing false-negative test results. Emitting an indistinguishable-from-Chrome handshake forces the censor to either pass-through diagnostic traffic identically to user traffic (correct test) or block both (also a correct, if catastrophic, test).

RIPDPI's transport (VPN tunnel) already pins uTLS to v1.8.2 (existing `pin-utls-to-v1-8-2-...` task) for the same reason — the proxied user traffic must look like Chrome. The diagnostic side **must use the same client**, not a separate JSSE/Conscrypt path, otherwise:
1. The diagnostic probe's verdict applies to a ClientHello fingerprint different from what the user actually sends
2. A censor running ClientHello-fingerprint-based blocking shows up as inconsistent results between probes and live use

**Implementation surface:**

- `DiagnosticTlsClientFactory.create(fingerprint: TlsFingerprint = TlsFingerprint.CHROME_120): TlsClient` — single entry point
- `TlsClient`: thin interface — `connect(host: String, port: Int, sni: String?, timeoutMs: Long): TlsConnection`; `TlsConnection` has `read`, `write`, `close`, `peerCertificates`, `negotiatedAlpn`, `negotiatedVersion`
- Implementation delegates to the existing transport-side uTLS-equivalent (likely a Rust crate exposed via JNI per RIPDPI's `native/rust/` layout, given `pin-utls-to-v1-8-2-...` lives in `area: transport`)
- Fallback: if the native uTLS client cannot be loaded (e.g. unsupported architecture), fall back to JSSE with a logged warning so probes still run, just without fingerprint consistency

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/inetutil/` (Go uTLS usage) + RIPDPI's existing `pin-utls-to-v1-8-2-and-add-clienthello-fingerprint-regression-test.md`

**RIPDPI placement:**
- Factory: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DiagnosticTlsClientFactory.kt`
- Interface: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/TlsClient.kt`
- JNI bridge: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/UtlsNativeBridge.kt`

## Acceptance criteria

- [ ] `TlsClient` interface defined; `TlsConnection` interface defined with the 5 methods listed
- [ ] `DiagnosticTlsClientFactory.create()` returns a uTLS-equivalent client when native lib loadable; JSSE fallback otherwise
- [ ] `TlsFingerprint` enum: `CHROME_120` (default), `FIREFOX_115`, `IOS_SAFARI_17` — values must match what the transport side already supports per `pin-utls-to-v1-8-2-...`
- [ ] Existing diagnostic probes (`Tcp16FatHeaderProbe`, `DomainReachabilityScanner`, `RknLayeredProbePipeline`) refactored to use `TlsClient` instead of direct `SSLSocket` / `OkHttpClient` TLS
- [ ] `DiagnosticTlsClientFactory.usingFallback(): Boolean` — exposes whether the JSSE fallback is active so probe results can flag "fingerprint may be inconsistent with user traffic"
- [ ] ClientHello byte-level regression test: capture the bytes emitted to a `MockSocket`, assert they match a known-good Chrome 120 ClientHello fixture (within version-pinning tolerance)
- [ ] No threading issues: `TlsClient` instances are short-lived (one per probe); factory is a singleton
- [ ] Unit tests: factory returns native client when bridge loads; falls back when load fails; ClientHello byte-equality vs fixture

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DiagnosticTlsClientFactoryTest.kt`:
     - `returns_native_client_when_bridge_loads()` — fake `UtlsNativeBridge.isAvailable() = true`; assert factory returns native impl; fails until factory exists
     - `falls_back_to_jsse_when_bridge_unavailable()` — fake `isAvailable() = false`; assert factory returns JSSE impl; `usingFallback() == true`
     - `chrome_120_fingerprint_matches_fixture()` — connect via factory to `MockSocket`; capture bytes; assert byte-equal to `chrome_120_clienthello.bin` fixture
     - `firefox_115_fingerprint_matches_fixture()` — same, with `FIREFOX_115`
     - `factory_is_thread_safe()` — 16 concurrent `create()` calls; assert no exceptions, no shared mutable state corruption
     - `connection_releases_resources_on_close()` — instrument `MockSocket.close`; assert called when `TlsConnection.close()` invoked
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 6 fail
3. **Implement** — `TlsClient`, `DiagnosticTlsClientFactory`, JNI bridge, JSSE fallback; refactor existing probes
4. **Confirm green** — `./gradlew :core:diagnostics:test :core:diagnostics:connectedAndroidTest` (instrumented test verifies native bridge loads on real device)
5. **Refactor** — drop direct `SSLSocket` / `SSLContext` usage from diagnostic-side modules; lint rule banning these imports outside `DiagnosticTlsClientFactory`

## Definition of done

All 6 unit tests green. ClientHello byte-equality regression test green. All diagnostic probes route TLS through `DiagnosticTlsClientFactory`. `usingFallback()` surfaced in diagnostic results UI when active.

## Work log

- 2026-05-11: Confirmed the current repo uses `ripdpi-tls-profiles`/BoringSSL plus `NativeOwnedTlsHttpFetcher` and `DiagnosticsHttpClientFactory` as the owned TLS equivalent; no Go `refraction-networking/utls` dependency is present in the native workspace.
- 2026-05-11: Routed `DomainReachabilityScanner`, `Tcp16FatHeaderProbe`, and `RknLayeredProbePipeline` ViewModel-provided network probes through `DiagnosticsHttpClientFactory`, with focused tests proving the injected owned TLS client path is used.
- 2026-05-11: Added `DiagnosticsTlsClientState` on `DiagnosticsHttpClientFactory` and exposed the current Android OkHttp fingerprint-template fallback state from `DefaultOwnedTlsClientFactory`, with focused service coverage for the fallback reason.
- 2026-05-11: Threaded `DiagnosticsTlsClientState` into `DomainReachabilityResult`, `Tcp16ProbeResult`, and `RknCheckResult`, with focused tests proving fallback-active probe runs carry the state from their injected diagnostic TLS factory.
- 2026-05-11: Surfaced diagnostic TLS profile/mode metrics in the Domain Reachability, TCP16, and layered block-diagnosis tool UI mappers so fallback-active probe runs are visible in app-side diagnostic cards.
- 2026-05-11: Surfaced diagnostic TLS profile/mode rows in the aggregate suite Domain Reachability and TCP16 row mappers so fallback-active runs remain visible outside individual tool cards.

Remaining before close:

- Add the formal `TlsClient`/`TlsConnection` API or update the acceptance criteria to the existing `DiagnosticsHttpClientFactory`/`NativeOwnedTlsHttpFetcher` contract.
- Propagate `DiagnosticsTlsClientState` into export rendering, not just on-screen diagnostic rows.
- Add byte-level ClientHello fixture regression coverage for the native owned TLS profiles.
- Re-check direct diagnostic `SSLSocket`/`SSLContext` usage and keep only intentionally non-owned-TLS capability checks outside the factory path.
