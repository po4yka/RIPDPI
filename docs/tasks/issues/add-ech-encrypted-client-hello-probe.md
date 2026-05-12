---
title: Add Encrypted Client Hello (ECH) Readiness and Acceptance Probe
type: task
status: doing
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-12
---

- [ ] #task Add Encrypted Client Hello (ECH) Readiness and Acceptance Probe #repo/RIPDPI #area/diagnostics #status/doing ⏫

## Objective

Add `EchReadinessProbe` that, for each ECH-eligible target (Cloudflare, Fastly, Mozilla CDN), fetches the server's ECH config via HTTPS RR DNS (RFC 9460), attempts an ECH-protected TLS handshake (RFC 9737), and reports per-target `ECH_OK | ECH_REJECTED | ECH_NO_CONFIG | ECH_UNKNOWN_KEY | ECH_NETWORK_BLOCK`. Establishes whether the user's network permits the strongest current counter to SNI-based DPI.

## Context

ECH is the first deployable mechanism that defeats SNI-based DPI without requiring an outbound proxy. The TLS ClientHello carries an encrypted "inner" ClientHello (with the real SNI), wrapped in an "outer" ClientHello using a per-server public key fetched via HTTPS RR DNS. A DPI middlebox sees only a generic-looking TLS handshake to e.g. `cloudflare-ech.com` — it cannot read the real SNI to decide whether to block.

Cloudflare, Fastly, and Mozilla CDN have all enabled ECH on their fronts; Chrome 117+ negotiates ECH automatically when the client has the config. RIPDPI users in censored networks need to know:
1. Is the HTTPS RR DNS lookup itself reachable, or does the ISP block / strip the record? → `ECH_NO_CONFIG`
2. When ECH config is available, does the TLS handshake succeed? → `ECH_OK`
3. Does the network specifically reject ECH'd handshakes (selective DPI)? → `ECH_REJECTED`
4. Does the server reject the offered key (out-of-date config)? → `ECH_UNKNOWN_KEY`

Detection ordering: this probe must be run **after** `add-domain-reachability-scanner` for the same target so we know whether vanilla TLS works at all. ECH-success on a network where vanilla TLS to the same target is blocked is the headline win — proves ECH bypasses the local DPI.

**Implementation:** uses `add-utls-diagnostic-probe-clienthello-fingerprinting` for the ECH'd handshake (uTLS supports ECH per `refraction-networking/utls` 1.6+; the existing transport-side pin must satisfy this). HTTPS RR DNS via `DnsWireBuilder` from `add-dns-integrity-checker` extended for type-65 records (RFC 9460).

**Reference:**
- RFC 9737 (TLS Encrypted Client Hello)
- RFC 9460 (HTTPS and SVCB DNS records)
- Cloudflare ECH announcement: https://blog.cloudflare.com/announcing-encrypted-client-hello/

**RIPDPI placement:**
- Probe: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/EchReadinessProbe.kt`
- HTTPS RR resolver: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/HttpsRrResolver.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/EchProbeResult.kt`
- Bundled targets: `core/diagnostics/src/main/assets/dpi/ech_targets.txt`

## Acceptance criteria

- [ ] `EchProbeResult`: `target: String`, `verdict (ECH_OK | ECH_REJECTED | ECH_NO_CONFIG | ECH_UNKNOWN_KEY | ECH_NETWORK_BLOCK)`, `httpsRrFetched: Boolean`, `echConfigBytesB64: String?`, `tlsLatencyMs: Long?`, `negotiatedEch: Boolean`, `errorDetail: String?`
- [ ] `HttpsRrResolver.fetch(host: String): HttpsRrRecord?` queries DoH for type-65 records; reuses `DnsWireBuilder` with type-65 encoding; falls back to system resolver if DoH unavailable
- [ ] `HttpsRrRecord` parses RFC 9460 SvcParamKeys including `ech` (the SvcParam carrying the ECHConfigList)
- [ ] ECH'd TLS handshake via `add-utls-diagnostic-probe-clienthello-fingerprinting` with `setEchConfig(bytes)` API on the uTLS bridge
- [ ] Verdict logic:
  - HTTPS RR lookup fails → `ECH_NO_CONFIG`
  - ECH config present but TLS handshake fails with "encrypted_client_hello" alert → `ECH_REJECTED` (DPI rejecting ECH specifically)
  - TLS handshake fails with "ech_required" alert → `ECH_UNKNOWN_KEY` (server config rotated; client retry-config flow not implemented in this task)
  - TLS connect fails at TCP layer → `ECH_NETWORK_BLOCK`
  - TLS handshake succeeds AND `ssock.getEchAccepted() == true` → `ECH_OK`
- [ ] Default target list: `cloudflare.com`, `fastly.com`, `mozilla.org`, `cloudflare-ech.com` (Cloudflare's public ECH test domain)
- [ ] User-override at `filesDir/dpi/ech_targets.txt`
- [ ] Cross-reference with reachability: probe attaches `vanillaTlsOk: Boolean?` from a recent `DomainReachabilityScanner` result (when available) — UI highlights "ECH works where vanilla TLS doesn't"
- [ ] Concurrency: max 4 simultaneous probes (ECH adds ~50ms vs vanilla TLS; don't blast)
- [ ] Unit tests: HTTPS RR parsing fixture; ECH alert classification; vanilla-vs-ECH cross-reference

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/EchReadinessProbeTest.kt`:
     - `https_rr_returns_ech_config_records_handshake()` — fake HTTPS RR with ECH SvcParam; mock TLS bridge returns `getEchAccepted() = true`; assert `ECH_OK`; fails until probe exists
     - `missing_https_rr_returns_no_config()` — DoH returns empty for type-65; assert `ECH_NO_CONFIG`
     - `ech_alert_returns_rejected()` — TLS handshake throws with alert "encrypted_client_hello"; assert `ECH_REJECTED`
     - `ech_required_alert_returns_unknown_key()` — alert "ech_required"; assert `ECH_UNKNOWN_KEY`
     - `tcp_layer_failure_returns_network_block()` — TCP connect throws timeout; assert `ECH_NETWORK_BLOCK`
     - `vanilla_tls_blocked_but_ech_ok_flagged_in_ui_signal()` — pass `vanillaTlsOk = false`; assert result carries `bypassedDpi = true`
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/HttpsRrResolverTest.kt`:
     - `parses_ech_svcparam_from_rr_record()` — feed RFC 9460 fixture with `ech` SvcParam; assert correct ECHConfigList bytes returned
     - `multiple_priorities_picks_highest()` — multiple HTTPS RRs at different priorities; assert priority-1 (highest) selected
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `EchReadinessProbe`, `HttpsRrResolver`, uTLS bridge `setEchConfig` extension, target asset
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract HTTPS RR / SvcParam parsing into a self-contained `Rfc9460Codec` utility

## Definition of done

All 8 unit tests green. ECH probe surfaced in DiagnosticsScreen Tools as "ECH Readiness" card. Per-target verdict + side-by-side comparison against vanilla TLS reachability. uTLS bridge extended with ECH support and gated behind native-availability check.

## Work log

- 2026-05-12: Added the local JVM-testable DNS/ECH foundation: DNS type-65 HTTPS query support, RFC 9460 HTTPS RR `ech` SvcParam parsing, a DoH wire HTTPS RR resolver, and injectable ECH readiness verdict classification for `ECH_NO_CONFIG`, `ECH_OK`, `ECH_REJECTED`, `ECH_UNKNOWN_KEY`, and `ECH_NETWORK_BLOCK`. Remaining close gates: native ECH handshake bridge, default target/user override wiring, suite/tool UI surfacing, and real ECH validation beyond fake JVM handshakes.
- 2026-05-12: Added bundled ECH target loading for `cloudflare.com`, `fastly.com`, `mozilla.org`, and `cloudflare-ech.com`, including `filesDir/dpi/ech_targets.txt` override support. Added `EchReadinessProbe.checkAll()` with the required max-4 concurrency cap and regression coverage for the cap.
- Verified with `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.dpi.EchReadinessProbeTest --tests com.poyka.ripdpi.diagnostics.dpi.DpiAssetLoaderTest -Pripdpi.skipNativeBuild=true`.
- Remaining close gates: native ECH handshake bridge, suite/tool UI surfacing, cross-reference wiring from recent domain reachability results, and real ECH validation beyond fake JVM handshakes.
