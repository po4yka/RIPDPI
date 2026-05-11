---
title: Add QUIC and HTTP/3 Fingerprint Probe for Selective UDP DPI Detection
type: task
status: doing
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-11
---

- [ ] #task Add QUIC and HTTP/3 Fingerprint Probe for Selective UDP DPI Detection #repo/RIPDPI #area/diagnostics #status/doing ⏫

## Objective

Add `QuicH3FingerprintProbe` that emits a Chrome-shaped QUIC Initial+0-RTT to each test target, watches for selective drop / RST / version-negotiation rejection, and reports `QUIC_OK`, `QUIC_DROPPED` (Initial packet silently discarded), `QUIC_VN_REJECTED` (version negotiation refusal), or `QUIC_DPI_FINGERPRINT_BLOCK` (specific Chrome fingerprint dropped while a different fingerprint succeeds).

## Context

Active L7 middleboxes have been observed degrading QUIC traffic since 2024 — selective drop of QUIC Initial packets, forced fallback to TCP, and fingerprint-aware handling that drops only Chrome-shaped Initials while letting Firefox-shaped or generic Initials through. None of the reference diagnostic tools measures this layer; they all stop at TLS-over-TCP.

This probe sits between `add-tcp16-fat-header-dpi-probe` (TCP-side byte-counting) and `add-domain-reachability-scanner` (TLS-over-TCP layered cascade) in the diagnostic suite. It answers: *"is the censor differentiating QUIC from TCP, and if so, by raw drop or by fingerprint?"*

**Detection ladder (per target):**
1. **Sanity probe** — generic UDP send/receive to `<target>:443` to confirm UDP path works at all
2. **Chrome QUIC v1 Initial** — emit a Chrome-fingerprinted QUIC Initial; await Server Initial within 3 RTT
3. **Firefox QUIC v1 Initial** — same but Firefox fingerprint; comparator
4. **Generic QUIC v1 Initial** — neutral fingerprint (just RFC 9000 minimums)
5. **Version negotiation probe** — send Initial with reserved version `0x1a2a3a4a`; valid server response is a VN packet listing supported versions; absence indicates path-level QUIC drop

**Verdict logic:**
- All 4 succeed → `QUIC_OK`
- All 4 fail at packet-drop level (no Server Initial) → `QUIC_DROPPED` (path drops UDP/443 indiscriminately)
- VN probe fails but valid-version probes succeed → `QUIC_VN_REJECTED` (rare; path-fingerprint-aware)
- Chrome fails but Firefox or generic succeeds → `QUIC_DPI_FINGERPRINT_BLOCK` (the headline finding — censor doing fingerprint-aware QUIC blocking)
- Mixed → `QUIC_DEGRADED` with detail breakdown

**Implementation reuses** the netty QUIC stack from `add-doq-dns-over-quic-integrity-probe`. The fingerprint variation comes from controlling: TLS extension order, ALPN list ordering, TLS version offered, cipher suite list, transport-parameter ordering, padding pattern. Pre-recorded byte fixtures from real Chrome 120 / Firefox 121 captures are bundled and mutated per probe to add the right CRYPTO frame contents.

**Targets:** the same target cohorts as `DomainReachabilityScanner`. Probe runs in parallel with the TCP-layer scan to provide a clear "QUIC vs TCP" comparison.

**Reference:** RFC 9000 (QUIC), RFC 9001 (QUIC-TLS), Chrome's `tools/quic/cert_compressor` for fingerprint examples; see also Tor Project's "QUIC censorship" 2025 incident reports.

**RIPDPI placement:**
- Probe: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/QuicH3FingerprintProbe.kt`
- Fingerprint codec: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/QuicFingerprintFactory.kt`
- Bundled fingerprint fixtures: `core/diagnostics/src/main/assets/dpi/quic_fingerprints/{chrome120,firefox121,generic_v1}.bin`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/QuicProbeResult.kt`

## Acceptance criteria

- [ ] `QuicProbeResult`: `target: String`, `verdict (QUIC_OK | QUIC_DROPPED | QUIC_VN_REJECTED | QUIC_DPI_FINGERPRINT_BLOCK | QUIC_DEGRADED | QUIC_TIMEOUT)`, `chromeOk: Boolean`, `firefoxOk: Boolean`, `genericOk: Boolean`, `vnOk: Boolean`, `udpReachable: Boolean`, `serverInitialLatencyMs: Long?`
- [ ] `QuicFingerprintFactory.create(fingerprint: QuicFingerprint, target: String): ByteArray` — emits a fingerprint-specific Initial packet; `QuicFingerprint` enum: `CHROME_120`, `FIREFOX_121`, `GENERIC_V1`, `VN_PROBE` (reserved-version Initial)
- [ ] Chrome / Firefox fingerprints loaded from bundled fixtures; CRYPTO frame contents (the inner ClientHello) generated per-probe with correct SNI/random
- [ ] Sanity UDP-reachability probe runs first — if `udpReachable == false`, skip QUIC probes and return `QUIC_DROPPED`
- [ ] Per-fingerprint probe: send Initial via `DatagramSocket`; wait up to 3s for Server Initial; classify success by detecting QUIC long-header version 0x00000001 in response
- [ ] VN probe: Initial with `version = 0x1a2a3a4a`; valid response = QUIC long-header with `version = 0x00000000` (VN packet); detect properly
- [ ] Verdict aggregation per the rules above; `QUIC_DPI_FINGERPRINT_BLOCK` is the headline finding (rare and politically interesting)
- [ ] Concurrency: per-target sequential (4 fingerprint probes); across targets max 8 in parallel
- [ ] Unit tests: fingerprint-byte regression (assert generated Initials byte-equal to fixtures within version/SNI/random tolerance); per-verdict path
- [ ] Instrumented test: real probe against `cloudflare.com:443` UDP from CI device; `chromeOk == true` expected

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/QuicH3FingerprintProbeTest.kt`:
     - `all_4_fingerprints_succeed_returns_ok()` — fake socket returns Server Initial for all; assert `QUIC_OK`; fails until probe exists
     - `udp_unreachable_short_circuits_to_dropped()` — fake UDP sanity fails; assert `QUIC_DROPPED`, no QUIC probes attempted
     - `chrome_fails_others_succeed_returns_fingerprint_block()` — fake socket drops Chrome fingerprint, accepts Firefox + generic; assert `QUIC_DPI_FINGERPRINT_BLOCK`
     - `vn_probe_fails_others_succeed_returns_vn_rejected()` — fake VN Initial drops but v1 Initials succeed; assert `QUIC_VN_REJECTED`
     - `all_fingerprints_drop_returns_dropped()` — UDP reaches but no Server Initial for any; assert `QUIC_DROPPED`
     - `mixed_results_returns_degraded()` — Chrome+Firefox OK, generic fails; assert `QUIC_DEGRADED` with breakdown
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/QuicFingerprintFactoryTest.kt`:
     - `chrome_120_initial_byte_equal_to_fixture_within_tolerance()` — generate; mask SNI/random fields; assert byte-equal to `chrome120.bin` mask
     - `vn_probe_uses_reserved_version()` — generate VN probe; assert version field == `0x1a2a3a4a`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `QuicH3FingerprintProbe`, `QuicFingerprintFactory`, fixture bundling, netty QUIC integration
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — share Server-Initial detection with `DoqQuicClient` (both detect `0x00000001` long-header)

## Definition of done

All 8 unit tests green. QUIC probe surfaced in DiagnosticsScreen as "QUIC / HTTP-3 Fingerprint" card with per-target × per-fingerprint matrix. Fingerprint fixtures auditable in `assets/`. Documentation note in PR explaining how to update fixtures when Chrome ClientHello drifts.

## Work log

- 2026-05-11: Added core QUIC/H3 result model, fingerprint enum, packet factory, UDP socket abstraction, verdict ladder, and deterministic unit tests in `core/diagnostics`.
- 2026-05-11: Added `QUIC_H3` to the DPI probe suite, including runner/controller/card wiring and aggregate verdict classification.
- 2026-05-11: Fixed the UDP sanity stage so the VN probe remains independently classified as `QUIC_VN_REJECTED`.
- 2026-05-11: Wired `QuicFingerprintFactory` to the native `ripdpi-packets` QUIC Initial builder through JNI, with JVM fallback coverage for missing native libraries.

Remaining before close:

- Add byte-regression tests for the native Chrome/Firefox/generic Initials with deterministic tolerance around per-probe random/SNI fields.
- Add a gated Android network smoke test for a known HTTP/3 endpoint once the packet factory emits valid QUIC Initials.
- Re-check the final task acceptance list and delete this note only when all criteria are covered.
