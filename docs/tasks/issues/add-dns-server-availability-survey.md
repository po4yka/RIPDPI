---
title: Add DNS Server Availability and Latency Survey
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: [add-dpi-error-classifier, add-ip-fake-ip-classifier]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add DNS Server Availability and Latency Survey #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `DnsAvailabilitySurvey` that probes 21 public DNS servers (9 UDP + 12 DoH Wire RFC 8484) and produces a per-server availability and average latency table, surfaced in the DiagnosticsScreen Tools section.

## Context

dpi-detector's Test 2 (`check_dns_availability`) tells users which DNS resolvers are reachable and fast from their network. This is actionable: a user seeing Yandex DNS timing out but Cloudflare DoH working can switch their DNS. The Android port reuses `DnsWireBuilder` from `add-dns-integrity-checker` for the UDP probe and OkHttp HTTP/2 for DoH Wire.

**Key implementation details from dpi-detector:**
- UDP timing: `System.nanoTime()` captured inside the `DatagramSocket` receive callback (before coroutine scheduling overhead) — same precision as Python's `time.perf_counter()` inside `datagram_received()`
- DoH Wire: one OkHttp client per server with HTTP/2 ALPN; warmup request before timing to exclude TLS handshake; POST → GET fallback; hard coroutine timeout = `timeout + 2000ms` to prevent double-timeout penalty
- Concurrency: max 15 simultaneous UDP sockets (semaphore)
- Incremental transaction IDs (not random) to avoid collision across concurrent probes

**21 servers to probe:**

UDP (9): Google 8.8.8.8, Cloudflare 1.1.1.1, Quad9 9.9.9.9, AdGuard 94.140.14.14, Yandex 77.88.8.8, OpenDNS 208.67.222.222, ControlD 76.76.2.0, CleanBrowsing, NextDNS

DoH Wire (12): Google dns.google, Cloudflare, AdGuard, Quad9, OpenDNS, Yandex, NextDNS, CleanBrowsing, DNS.SB, LibreDNS

Test domains: `example.com`, `vk.com`, `ozon.ru`, `habr.com`, `mail.ru`

**Reference**: `/Users/po4yka/GitRep/dpi-detector/core/dns_scanner.py` — `check_dns_availability`, `_probe_udp_single` (timing variant), `_probe_doh_wire_single`

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsAvailabilitySurvey.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsServerResult.kt`

## Acceptance criteria

- [ ] All 21 servers probed; individual failures recorded as `TIMEOUT` in result, not exceptions
- [ ] UDP timing captured at socket receive level (not after coroutine dispatch)
- [ ] DoH Wire warmup request excludes TLS handshake from latency measurement
- [ ] DoH Wire POST → GET fallback implemented
- [ ] Hard timeout = `configuredTimeout + 2000ms` applied to each DoH probe via `withTimeout`
- [ ] UDP concurrency limited to 15 simultaneous sockets via semaphore
- [ ] `DnsServerResult`: `name`, `type (UDP|DOH_WIRE)`, `availableDomains: Int`, `totalDomains: Int`, `avgLatencyMs: Long?` (null if all failed)
- [ ] Results sorted: available servers first (by latency), then timeouts
- [ ] UI: results table shown in DiagnosticsScreen Tools section with latency column and availability count
- [ ] Unit tests: mock `DatagramSocket` with known timing; mock OkHttp DoH Wire client; assert latency calculation and TIMEOUT handling

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DnsAvailabilitySurveyTest.kt`:
     - `timeout_server_recorded_as_unavailable()` — inject server that throws `SocketTimeoutException`; assert `availableDomains=0` and `avgLatencyMs=null`; fails until survey exists
     - `successful_server_records_latency()` — inject server returning valid DNS response; assert `avgLatencyMs > 0`
     - `doh_wire_fallback_to_get_when_post_returns_405()` — mock OkHttp returning 405 for POST; assert GET attempted; assert result non-null
     - `results_sorted_available_first()` — inject 1 available + 1 timeout server; assert available server first in result list
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 4 fail
3. **Implement** — `DnsAvailabilitySurvey`, `DnsServerResult`, UI table
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — share `DnsWireBuilder` with `DnsIntegrityChecker`; extract server list to config

## Definition of done

All 4 unit tests green. DNS server latency table visible in DiagnosticsScreen Tools with sortable columns.
