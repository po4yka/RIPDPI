---
title: Add DNS Integrity Checker with Wire-Format UDP and DoH Comparison
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: [add-domain-reachability-scanner]
blocked_by: [add-dpi-error-classifier, add-ip-fake-ip-classifier, add-dpi-target-assets]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add DNS Integrity Checker with Wire-Format UDP and DoH Comparison #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `DnsIntegrityChecker` that probes each test domain via three independent DNS methods (raw UDP/53 wire format, DoH JSON API, DoH Wire RFC 8484), compares results to detect ISP DNS interception or substitution, and collects ISP stub IPs for cross-use in the domain reachability scanner.

## Context

This is the Android port of dpi-detector's Test 1 (`check_dns_integrity`). The key insight: the system resolver is bypassed entirely. Instead, the checker hand-builds RFC 1035 DNS wire-format packets and sends them directly to public DNS server IPs via `DatagramSocket`, then compares the results against DoH (trusted). Divergence = ISP manipulation.

**Three probe methods (run in parallel per domain):**

1. **UDP/53 native** — raw `DatagramSocket` to each DNS server IP:53; hand-built A-record query; random 2-byte transaction ID; 2 retries with 500ms sleep
2. **DoH JSON API** — OkHttp GET to `https://<server>/resolve?name=<domain>&type=A` with `Accept: application/dns-json`
3. **DoH Wire RFC 8484** — OkHttp HTTP/2 POST with `Content-Type: application/dns-message` (raw wire query); fallback to GET `?dns=<base64url>` if POST returns non-200

**Classification per domain:**
- UDP IPs ∩ DoH IPs non-empty → `DNS_OK`
- UDP returns `198.18.x.x` (Fake-IP range) → `FAKE_IP` (VPN tun, not ISP)
- UDP returns different IPs from DoH → `DNS_SUBSTITUTION` (ISP DNS manipulation)
- UDP times out, DoH succeeds → `DNS_INTERCEPTION` (ISP intercepts and drops UDP/53)
- UDP returns NXDOMAIN, DoH resolves → `FAKE_NXDOMAIN`
- DoH returns HTTP non-200 → counter increment `dohBlocked`

**Stub IP collection:** IPs returned by UDP for ≥2 domains are collected as ISP stub IPs, passed to `DomainReachabilityScanner` for ISP page pre-detection.

**Reference**: `/Users/po4yka/GitRep/dpi-detector/core/dns_scanner.py` — `check_dns_integrity`, `_build_dns_query`, `_parse_dns_response`, `_probe_udp_single`, `_probe_doh_json_single`, `_probe_doh_wire_single`

**DNS servers used** (from `config.yml`): Google 8.8.8.8, Cloudflare 1.1.1.1, Quad9 9.9.9.9, AdGuard 94.140.14.14, Yandex 77.88.8.8, OpenDNS 208.67.222.222

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsIntegrityChecker.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsWireBuilder.kt` — RFC 1035 packet builder/parser
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsIntegrityResult.kt`

## Acceptance criteria

- [ ] `DnsWireBuilder.buildQuery(domain)` produces valid RFC 1035 A-record query bytes (random 2-byte tx ID, RD=1)
- [ ] `DnsWireBuilder.parseResponse(bytes, txId)` returns list of IPv4 strings, `"NXDOMAIN"`, or `"PARSE_ERR"`; handles pointer compression
- [ ] UDP probe sends via `DatagramSocket` (not system resolver); retries twice on timeout
- [ ] All 3 methods run in parallel via `async`/`coroutineScope`; individual failures do not abort the domain check
- [ ] Classification into 5 categories as described; `IpAddressClassifier` used for Fake-IP detection
- [ ] Stub IPs collected: IPs appearing in ≥2 domain UDP responses; exposed in `DnsIntegrityResult.stubIps`
- [ ] `dohBlocked` counter in result
- [ ] Domains tested from `DpiAssetLoader.loadDomains()` (subset used for DNS, from config)
- [ ] Unit tests with mock `DatagramSocket` and mock OkHttp responses

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsWireBuilderTest.kt`:
     - `build_query_produces_valid_a_record_wire_format()` — assert bytes at offset 2-3 = flags `0x01 0x00`; offset 4-5 = `0x00 0x01` (1 question); fails until builder exists
     - `parse_response_extracts_ipv4_from_valid_response()` — feed pre-captured DNS response bytes; assert correct IP list
     - `parse_response_returns_nxdomain_on_rcode_3()` — feed NXDOMAIN response bytes; assert `["NXDOMAIN"]`
     - `parse_response_handles_pointer_compression()` — feed response with name compression pointer; assert no crash
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsIntegrityCheckerTest.kt`:
     - `substitution_detected_when_udp_and_doh_ips_differ()` — mock UDP returns `1.2.3.4`, mock DoH returns `5.6.7.8`; assert `DNS_SUBSTITUTION`
     - `interception_detected_when_udp_times_out_doh_succeeds()` — mock UDP timeout, mock DoH success; assert `DNS_INTERCEPTION`
     - `fake_ip_detected_when_udp_returns_198_18_range()` — mock UDP returns `198.18.0.1`; assert `FAKE_IP`
     - `stub_ips_collected_from_repeated_udp_responses()` — 3 domains all return same IP via UDP; assert `stubIps` contains that IP
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `DnsWireBuilder`, `DnsIntegrityChecker`, result models
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract DNS server list to config; make retry count and timeout configurable

## Definition of done

All 8 unit tests green. `DnsIntegrityChecker` surfaced in DiagnosticsScreen Tools section with per-domain result table. `stubIps` passed to `DomainReachabilityScanner`.
