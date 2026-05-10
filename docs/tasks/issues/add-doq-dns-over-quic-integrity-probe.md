---
title: Add DNS-over-QUIC (DoQ) Integrity Probe for UDP-853 Censorship Detection
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: [add-dns-integrity-checker, add-dpi-error-classifier]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add DNS-over-QUIC (DoQ) Integrity Probe for UDP-853 Censorship Detection #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `DoqIntegrityProbe` that resolves test domains via DNS-over-QUIC (RFC 9250) against AdGuard, Cloudflare, and dns.google DoQ endpoints, compares results with the same domains resolved via DoH, and reports `DOQ_OK`, `DOQ_BLOCKED_QUIC` (UDP/853 dropped), `DOQ_DPI_REJECT` (QUIC handshake selectively blocked), or `DOQ_INTEGRITY_DIVERGENT` (DoQ returns different IPs from DoH for the same name).

## Context

dpi-detector and rkn-block-checker measure DNS at three layers (UDP/53 wire, DoH JSON, DoH Wire). None probe DoQ. This is a meaningful gap: Russian TSPU has been observed selectively blocking QUIC traffic (UDP) since late 2024 — a probe that succeeds via DoH but fails via DoQ uniquely identifies UDP/QUIC censorship that's invisible to TCP-based DNS checks.

DoQ uses QUIC as the transport for DNS messages encoded per RFC 8484 wire format. Default port is **853 over UDP**, the same number as DoT but UDP not TCP.

**Verdict matrix (for each test domain × each DoQ provider):**
- DoQ resolves AND result matches DoH → `DOQ_OK`
- DoQ handshake fails at QUIC layer (Initial packet dropped, version negotiation failure) → `DOQ_BLOCKED_QUIC` (UDP/853 censorship)
- DoQ handshake fails at TLS layer (ALPN rejection, cert mismatch) → `DOQ_DPI_REJECT` (QUIC permitted but DoQ specifically rejected)
- DoQ resolves AND result diverges from DoH → `DOQ_INTEGRITY_DIVERGENT` (provider compromised — unlikely, but worth flagging)
- DoQ times out → `DOQ_TIMEOUT` (could be QUIC block, could be flake)

**Provider list (default):**
- AdGuard: `quic://dns.adguard-dns.com:853`
- Cloudflare: `quic://1.1.1.1:853`
- Google: `quic://dns.google:853`
- NextDNS: `quic://dns.nextdns.io:853`

**QUIC implementation:** Android JVM's QUIC support is nascent. Two viable paths:
1. **netty-codec-http3** (`io.netty:netty-codec-http3`) — actively maintained, supports QUIC v1, includes BoringSSL
2. **OkHttp 5.0+ with `Protocol.HTTP_3`** — but this is HTTP/3, not bare QUIC; need to wrap DNS messages in HTTP/3 frames (RFC 9484)

Recommend netty since it's lighter weight and exposes raw QUIC streams (which DoQ requires per RFC 9250 §4.2).

**Reference:** RFC 9250 (DoQ), RFC 9000 (QUIC), AdGuard DoQ docs

**RIPDPI placement:**
- Probe: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DoqIntegrityProbe.kt`
- QUIC client: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DoqQuicClient.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DoqProbeResult.kt`

## Acceptance criteria

- [ ] `DoqProbeResult`: `provider: String`, `endpoint: String`, `domain: String`, `verdict (DOQ_OK | DOQ_BLOCKED_QUIC | DOQ_DPI_REJECT | DOQ_INTEGRITY_DIVERGENT | DOQ_TIMEOUT)`, `resolvedIps: List<String>`, `latencyMs: Long?`, `errorDetail: String?`
- [ ] `DoqQuicClient.resolve(endpoint: String, port: Int, domain: String, timeoutMs: Long): List<String>` — opens QUIC connection with ALPN `doq`, opens a stream, sends RFC 1035 wire-format A query, reads response, parses IPs
- [ ] netty-codec-http3 dependency added to `core/diagnostics/build.gradle.kts`; gated behind a setting `dpi.diagnostics.includeQuic` (default ON; opt-out for users on devices without BoringSSL native lib support)
- [ ] QUIC layer error → `DOQ_BLOCKED_QUIC`: detected via netty's `QuicException` or no Initial-ACK within timeout
- [ ] TLS layer error → `DOQ_DPI_REJECT`: detected via TLS alert codes from netty's `TlsException`
- [ ] DoQ-vs-DoH cross-check: probe takes `dohResults: Map<String, List<String>>` from `DnsIntegrityChecker`; if DoQ IPs disjoint from DoH IPs → `DOQ_INTEGRITY_DIVERGENT`
- [ ] Default 4 providers + 5 test domains (reuse `DnsAvailabilitySurvey`'s domain list); 4 × 5 = 20 probes per run
- [ ] Concurrency: max 8 simultaneous QUIC connections (semaphore)
- [ ] Per-provider timeout 6s (QUIC handshake adds latency vs UDP)
- [ ] Unit tests: mock netty QUIC client; assert verdict matrix; assert DoH cross-check logic
- [ ] Instrumented test: real DoQ resolution against Cloudflare in CI (gated by `RIPDPI_RUN_NETWORK_TESTS=1` env)

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DoqIntegrityProbeTest.kt`:
     - `successful_doq_with_matching_doh_returns_ok()` — fake QUIC client returns `["1.2.3.4"]`; DoH crossref returns `["1.2.3.4"]`; assert `DOQ_OK`; fails until probe exists
     - `quic_handshake_failure_returns_blocked_quic()` — fake QUIC client throws `QuicException`; assert `DOQ_BLOCKED_QUIC`
     - `tls_alert_returns_dpi_reject()` — fake QUIC throws `TlsException("handshake_failure")`; assert `DOQ_DPI_REJECT`
     - `divergent_ips_returns_integrity_divergent()` — DoQ `["1.2.3.4"]`, DoH `["5.6.7.8"]`; assert `DOQ_INTEGRITY_DIVERGENT`
     - `timeout_returns_doq_timeout()` — QUIC client never responds; assert `DOQ_TIMEOUT` after 6s
     - `concurrency_capped_at_8()` — instrument; 20 probes; assert max 8 in-flight
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DoqQuicClientTest.kt`:
     - `wire_format_query_sent_on_doq_stream()` — capture bytes written to fake netty stream; assert RFC 1035 query bytes prefixed by 2-byte length per RFC 9250 §4.2.1
     - `wire_format_response_parsed_into_ip_list()` — feed pre-captured DoQ response; assert correct IP list
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `DoqIntegrityProbe`, `DoqQuicClient` over netty, RFC 9250 stream framing
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — share wire-format query encoder with existing `DnsWireBuilder` (RFC 1035 codec is identical; only the framing differs)

## Definition of done

All 8 unit tests green. DoQ probe surfaced as a row in DNS integrity card. CI integration test green against Cloudflare DoQ (when network tests enabled). netty QUIC dependency size impact documented in PR description (~2-3 MB APK).
