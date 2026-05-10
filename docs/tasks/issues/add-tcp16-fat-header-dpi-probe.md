---
title: Add TCP16 FAT-Header DPI Probe with Keep-Alive RST Detection
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: [add-whitelist-sni-finder]
blocked_by: [add-dpi-error-classifier, add-dpi-target-assets]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add TCP16 FAT-Header DPI Probe with Keep-Alive RST Detection #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `Tcp16FatHeaderProbe` that issues 16 sequential `HEAD` requests over a single keep-alive HTTP/1.1 connection to each of 140 CDN/hosting IPs from `tcp16.json`, padding each request after the first with a 4KB random `X-Pad` header to detect Russian TSPU's 16-20KB cumulative-byte RST injection.

## Context

This is the Android port of dpi-detector's Test 4 (`check_tcp_16_20`). Russian TSPU DPI engines apply per-flow byte-counting and inject TCP RST when cumulative client→server bytes cross the 16-20KB threshold. The probe exploits this by:
1. Opening a single keep-alive HTTP/1.1 socket to each test target
2. Sending request `i=0` clean (no padding) — establishes baseline RTT and confirms target is alive
3. Sending requests `i=1..15` each carrying a 4KB random `X-Pad` header — accumulates 4KB × 15 = 60KB of upload payload
4. If TSPU is active, the connection RSTs around request 4-5 (16-20KB cumulative); record `DETECTED at <i*4>KB`
5. If all 16 succeed, target is `OK`

**Critical implementation details:**
- **Single socket reuse**: `OkHttpClient` with `ConnectionPool(maxIdleConnections=1, keepAliveDuration=∞)` and a custom `Interceptor` that asserts each request reuses the same `Connection`. If OkHttp opens a new socket mid-batch, the test result is invalid (false negative).
- **Adaptive timeout**: first 2 requests measure RTT; subsequent requests use `dynamic_timeout = max(rtt × 3, 1.5s)` capped at `FAT_READ_TIMEOUT (10s)`. Prevents long timeouts on fast networks.
- **`hint_rtt` mode**: variant `runWithRttHint(rtt)` skips RTT measurement (used by whitelist SNI finder which already has the RTT from the initial probe).
- **Random padding**: pre-generate a 100KB pool of random ASCII at probe-init time; per-request slice 4KB at a random offset. Faster than per-request `Random.nextBytes()`.
- **TLS verify-off**: targets are raw IPs (not domains) so `HostnameVerifier.ALLOW_ALL` + `X509TrustManager` accepting all certs. The point is to detect TCP-layer DPI, not TLS validity.
- **No HTTP/2**: `OkHttpClient.protocols(listOf(Protocol.HTTP_1_1))` — H2 multiplexes which defeats the per-flow byte counting heuristic.
- **Concurrency**: max 15 simultaneous probes via semaphore; 140 targets / 15 = ~10 batches.

**Result classification per target:**
- All 16 requests OK → `OK`
- Request 0 fails → `target dead` (excluded from DPI tally)
- Requests 1-15 fail at byte K (16-20 KB) → `DETECTED at K KB` (TSPU positive)
- Other failure → use `DpiErrorClassifier`

**Reference:** `/Users/po4yka/GitRep/dpi-detector/core/tcp16_scanner.py` — `_fat_probe_keepalive`, `check_tcp_16_20`, `check_tcp_16_20_with_rtt`

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/Tcp16FatHeaderProbe.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/Tcp16ProbeResult.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/RandomPaddingPool.kt`

## Acceptance criteria

- [ ] `Tcp16ProbeResult`: `targetId`, `asn`, `provider`, `ip`, `port`, `alive: Boolean`, `verdict: Tcp16Verdict (OK | DETECTED_AT_KB | DEAD | ERROR)`, `detectedAtKb: Int?`, `measuredRttMs: Long?`, `errorDetail: String?`
- [ ] `RandomPaddingPool`: 100KB pre-generated ASCII pool; `slice(size: Int): String` returns a 4KB substring at random offset
- [ ] Single-socket enforcement: custom `EventListener` asserts `connectStart` fires only once per target; if it fires twice → result marked `INVALID_RECONNECTED`
- [ ] OkHttp configured: `ConnectionPool(1, Long.MAX_VALUE, NANOSECONDS)`, `protocols([HTTP_1_1])`, `hostnameVerifier(ALLOW_ALL)`, custom `X509TrustManager` accepting all
- [ ] Adaptive timeout: measure RTT on first 2 requests, then `dynamic = clamp(rtt × 3, 1.5s, FAT_READ_TIMEOUT)`
- [ ] `runWithRttHint(target, hintRtt)` variant: skips RTT measurement, applies `hintRtt × 3` immediately (for SNI finder)
- [ ] Per-request `X-Pad` header constructed from `RandomPaddingPool.slice(4000)` for `i ≥ 1`
- [ ] Concurrency: `Semaphore(15)` limits parallel probes
- [ ] Aggregation: results grouped by ASN; `byAsn(): Map<String, List<Tcp16ProbeResult>>` for UI table
- [ ] Unit tests with `MockWebServer` simulating: all-OK, RST at request 5, dead target, single-socket-violation

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/Tcp16FatHeaderProbeTest.kt`:
     - `all_16_requests_ok_returns_ok_verdict()` — `MockWebServer` returns 200 to all; assert `verdict == OK`; fails until probe exists
     - `connection_reset_at_request_5_returns_detected_at_20kb()` — server closes socket after request 5; assert `DETECTED_AT_KB` with `detectedAtKb == 20`
     - `dead_target_returns_dead_verdict()` — server unreachable; assert `verdict == DEAD`, `alive == false`
     - `single_socket_reuse_enforced()` — instrument OkHttp; assert exactly one `connectStart` event for 16 requests
     - `rtt_hint_skips_measurement_phase()` — call `runWithRttHint(target, 50ms)`; assert no extra RTT-measuring requests
     - `x_pad_header_present_on_requests_1_through_15()` — capture mock requests; assert request 0 has no `X-Pad`, requests 1-15 each have a unique 4KB `X-Pad`
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/RandomPaddingPoolTest.kt`:
     - `slice_returns_requested_size()` — `slice(4000)`; assert length 4000
     - `slice_returns_different_content_on_repeated_calls()` — 100 calls, all distinct (with high probability)
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `Tcp16FatHeaderProbe`, `RandomPaddingPool`, custom `EventListener`, `X509TrustManager` accept-all
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract OkHttp builder into `Tcp16ProbeClientFactory`; share with `add-whitelist-sni-finder`

## Definition of done

All 8 unit tests green. TCP16 probe surfaced in DiagnosticsScreen Tools section with per-ASN result table. `runWithRttHint` exposed for `WhitelistSniFinder` reuse.
