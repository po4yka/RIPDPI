---
title: Wire Owned TLS Client into Diagnostic Probes for ClientHello Fingerprint Consistency
type: task
status: doing
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: [add-tls-cert-sni-discoverer, add-ech-encrypted-client-hello-probe, add-tls-keylog-path-for-pcap-debug]
blocked_by: []
created: 2026-05-10
updated: 2026-05-11
---

- [ ] #task Wire Owned TLS Client into Diagnostic Probes for ClientHello Fingerprint Consistency #repo/RIPDPI #area/diagnostics #status/doing 🔼

## Objective

Route diagnostic HTTPS probes through the same owned TLS profile stack that user traffic uses, and surface the active
TLS profile/fallback state in probe results, UI rows, and exported summaries.

## Context

dpi-ch uses `refraction-networking/utls` to emit browser-like ClientHello fingerprints for diagnostic probes. RIPDPI does
not carry that Go dependency; the repo-local equivalent is `ripdpi-tls-profiles` plus the Android owned TLS fetcher path.
Diagnostic HTTPS probes must use that same owned stack contract, or explicitly report when they are on the Android
OkHttp template fallback, so diagnostic verdicts do not silently describe a different TLS fingerprint from runtime use.

**Implementation surface:**

- `DiagnosticsHttpClientFactory.createClient()` is the diagnostic entry point.
- `DefaultOwnedTlsClientFactory` implements `DiagnosticsHttpClientFactory` and `OwnedTlsClientFactory`.
- `NativeOwnedTlsHttpFetcher` is the native fetcher adapter for the owned TLS path.
- `DiagnosticsTlsClientState` reports the active profile id, native availability, fallback state, and fallback reason.
- `ripdpi-tls-profiles` owns the native ClientHello profile packet parity fixtures.

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/inetutil/` (Go uTLS usage) + RIPDPI's existing `pin-utls-to-v1-8-2-and-add-clienthello-fingerprint-regression-test.md`

**RIPDPI placement:**
- Contract: `core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsHttpClientFactory.kt`
- Factory: `core/service/src/main/kotlin/com/poyka/ripdpi/services/OwnedTlsClientFactory.kt`
- Native adapter: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/NativeOwnedTlsHttpFetcher.kt`
- Native profile tests: `native/rust/crates/ripdpi-tls-profiles/src/packet_parity_tests.rs`

## Acceptance criteria

- [x] `DiagnosticsHttpClientFactory` exposes a single diagnostic HTTP client construction path and TLS state reporting.
- [x] `DefaultOwnedTlsClientFactory` binds the diagnostic contract to the owned TLS factory.
- [x] Diagnostic probes (`Tcp16FatHeaderProbe`, `DomainReachabilityScanner`, `RknLayeredProbePipeline`) use the injected diagnostics factory instead of constructing independent TLS clients in the app-provided path.
- [x] `DiagnosticsTlsClientState` exposes profile id, native availability, fallback active state, and fallback reason.
- [x] Probe results carry `DiagnosticsTlsClientState` and UI mappers render profile/mode indicators.
- [x] Share/archive summary rendering includes persisted diagnostic TLS profile/mode/fallback details.
- [x] Native ClientHello fixture regression covers the owned profile packet surface.
- [x] Final audit confirms any remaining direct `SSLSocket`/`SSLContext` usage is intentionally non-owned-TLS capability code.

## TDD workflow

1. Add failing focused tests for each missing observable contract: injected probe client usage, TLS state propagation, UI rows, export rows, and native ClientHello fixture parity.
2. Implement the smallest slice that makes the focused test pass.
3. Run the matching module test and lint/architecture checks before committing each atomic slice.
4. Finish with a direct TLS usage audit and leave intentional raw TLS capability probes documented.

## Definition of done

Focused Kotlin probe/UI/export tests are green. Native ClientHello packet parity tests are green. Diagnostic HTTPS probes use
`DiagnosticsHttpClientFactory` in the app-provided path, `DiagnosticsTlsClientState` is visible in UI and exported
summaries, and the final direct TLS usage audit has no unowned diagnostic probe violations.

## Work log

- 2026-05-11: Confirmed the current repo uses `ripdpi-tls-profiles`/BoringSSL plus `NativeOwnedTlsHttpFetcher` and `DiagnosticsHttpClientFactory` as the owned TLS equivalent; no Go `refraction-networking/utls` dependency is present in the native workspace.
- 2026-05-11: Routed `DomainReachabilityScanner`, `Tcp16FatHeaderProbe`, and `RknLayeredProbePipeline` ViewModel-provided network probes through `DiagnosticsHttpClientFactory`, with focused tests proving the injected owned TLS client path is used.
- 2026-05-11: Added `DiagnosticsTlsClientState` on `DiagnosticsHttpClientFactory` and exposed the current Android OkHttp fingerprint-template fallback state from `DefaultOwnedTlsClientFactory`, with focused service coverage for the fallback reason.
- 2026-05-11: Threaded `DiagnosticsTlsClientState` into `DomainReachabilityResult`, `Tcp16ProbeResult`, and `RknCheckResult`, with focused tests proving fallback-active probe runs carry the state from their injected diagnostic TLS factory.
- 2026-05-11: Surfaced diagnostic TLS profile/mode metrics in the Domain Reachability, TCP16, and layered block-diagnosis tool UI mappers so fallback-active probe runs are visible in app-side diagnostic cards.
- 2026-05-11: Surfaced diagnostic TLS profile/mode rows in the aggregate suite Domain Reachability and TCP16 row mappers so fallback-active runs remain visible outside individual tool cards.
- 2026-05-11: Preserved injected diagnostic TLS clients when the aggregate suite uses a non-default concurrency override by cloning the existing Domain Reachability and TCP16 probes with their injected collaborators intact.
- 2026-05-11: Added a fixture-backed native ClientHello packet parity regression for `ripdpi-tls-profiles`, covering profile ids, ALPN, SNI, record and handshake lengths, GREASE counts, supported groups, key-share groups, extension data lengths, fixed-family extension order, and permuted-family extension sets.
- 2026-05-11: Rendered persisted diagnostic TLS profile/mode/fallback fields from probe details into share/archive summary raw previews so the TLS path state is export-visible as well as UI-visible.
- 2026-05-11: Reconciled the task contract with RIPDPI's actual `DiagnosticsHttpClientFactory`/`DefaultOwnedTlsClientFactory`/`NativeOwnedTlsHttpFetcher` owned-TLS API, then removed probe-local JSSE socket/context construction from Domain Reachability, TCP16, layered block-diagnosis TLS defaults, and WebhostFarm. Added a source regression that blocks reintroducing direct JSSE socket/context construction in those diagnostic probes.
- 2026-05-11: Unblocked WebhostFarm and CIDR-whitelist detector from the owned-TLS client task after wiring WebhostFarm through `DpiDiagnosticsToolModule` with `DiagnosticsHttpClientFactory`; ECH and keylog remain blocked because they require dedicated native APIs beyond the shared diagnostic HTTP client path.

Remaining before close:

- Reconcile downstream task blockers that still expect a Go/uTLS-style bridge API (`ECH`, TLS keylog, cert-SNI discovery) before deleting this task note.
