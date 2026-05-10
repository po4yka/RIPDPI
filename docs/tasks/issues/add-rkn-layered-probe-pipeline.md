---
title: Add Layered DNS-TCP-TLS-HTTP Probe Pipeline with Stop-at-First-Failure
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: rkn-block-checker-parity-epic
blocks: [add-rkn-control-vs-test-aggregate-verdict]
blocked_by: [add-dpi-error-classifier, add-rkn-system-doh-dns-comparison, add-rkn-stub-page-marker-detector, add-rkn-privacy-conscious-probe-headers]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Layered DNS-TCP-TLS-HTTP Probe Pipeline with Stop-at-First-Failure #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `RknLayeredProbePipeline.checkUrl(target)` that walks DNS → TCP → TLS → HTTP for one target, stops at the first layer that fails, and returns a `RknCheckResult` with the layer-specific verdict, confidence, and probe trace fields. Mirrors `rkn_checker/core.py` `check_url` exactly.

## Context

The defining structural property of rkn-block-checker is its **layered cascade**: each target is probed at four layers in strict order, and the **layer that breaks becomes the verdict**. This is in deliberate contrast to dpi-detector, which runs each test type independently across all targets. The cascade approach surfaces the *kind* of block immediately:

- DNS fails alone → `DNS_BLOCK` (poisoning)
- DNS OK, TCP fails with RST → `TCP_RESET`
- DNS+TCP OK, TLS fails → `TLS_BLOCK` (DPI on SNI — the modern TSPU signature)
- DNS+TCP+TLS OK, HTTP returns stub → `HTTP_STUB`
- All four OK → `OK`

The cascade also produces a clean confidence story: each layer can be verified independently, and verdicts at deeper layers presuppose success at earlier layers. A `TLS_BLOCK` with `tcp_ok = true` is the unambiguous DPI-on-SNI fingerprint; the same verdict with `tcp_ok = false` would be inconsistent.

**Per-layer behaviour (from `core.check_url`):**

1. **DNS** — `SystemDohDnsComparator.compare(host)` from `add-rkn-system-doh-dns-comparison`
   - `DNS_BLOCK` (system fails, DoH OK) → STOP, return verdict
   - `DOWN` (both fail) → STOP
   - `DNS_REWRITE` → record note, continue (downgrades final OK confidence to MEDIUM)
   - `OK` → continue
2. **TCP** — raw `Socket.connect(InetSocketAddress(host, 443), timeout)`
   - `SocketTimeoutException` → `TIMEOUT`, LOW
   - `ConnectException("Connection reset")` → `TCP_RESET`, MEDIUM
   - other → `DOWN`, LOW
   - success → continue, record `tcpOk = true, tcpTimeMs`
3. **TLS** — `SSLContext.getDefault().socketFactory.createSocket(host, 443)` + `startHandshake()` with SNI = host
   - `SSLException` containing "reset" → `TLS_BLOCK`, MEDIUM (TSPU signature)
   - timeout during handshake → `TLS_BLOCK`, MEDIUM
   - other SSL error → `TLS_BLOCK`, LOW
   - success → continue, record `tlsOk = true, tlsTimeMs, tlsCertCn`
4. **HTTP** — `OkHttpClient` `GET <url>` with generic Chrome headers from `add-rkn-privacy-conscious-probe-headers`
   - `RknStubPageDetector.detect(body, statusCode).isStub` → `HTTP_STUB`, HIGH
   - timeout → `TIMEOUT`, LOW
   - other error → `DOWN`, LOW
   - 200-499 (no stub) → `OK`, HIGH (or MEDIUM if `dnsMismatch`)

**Reference:** `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/core.py` `check_url` (lines 30-161)

**RIPDPI placement:**
- Pipeline: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknLayeredProbePipeline.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknCheckResult.kt`
- Verdict / confidence: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknVerdict.kt`

## Acceptance criteria

- [ ] `RknVerdict` enum: `OK`, `DNS_BLOCK`, `TCP_RESET`, `TLS_BLOCK`, `HTTP_STUB`, `TIMEOUT`, `DOWN`, `UNKNOWN`
- [ ] `RknConfidence` enum: `HIGH`, `MEDIUM`, `LOW`
- [ ] `RknCheckResult`: `name`, `url`, `verdict`, `confidence`, `notes: List<String>`, plus probe trace: `sysIps`, `dohIps`, `sysIp`, `dohIp`, `dnsMismatch`, `dnsError`, `tcpOk`, `tcpTimeMs`, `tcpError`, `tlsOk`, `tlsTimeMs`, `tlsCertCn`, `tlsError`, `statusCode`, `pltMs`, `httpError`
- [ ] Strict cascade: layer N runs only after layer N-1 succeeded; on failure, return immediately with all earlier-layer fields populated
- [ ] DNS layer: delegates to `SystemDohDnsComparator`; `DNS_BLOCK`/`DOWN` short-circuit; `DNS_REWRITE` continues with note
- [ ] TCP layer: raw `Socket.connect(InetSocketAddress(host, 443), timeoutMs)`; classifies timeout vs reset vs other
- [ ] TLS layer: `SSLSocket` with `setEnabledProtocols(["TLSv1.2", "TLSv1.3"])`; SNI set explicitly via `SSLParameters.setServerNames`; extracts cert CN
- [ ] HTTP layer: `OkHttpClient` `GET` with headers from `add-rkn-privacy-conscious-probe-headers`; allows redirects; 5s timeout; reads first 2000 chars of body for `RknStubPageDetector`
- [ ] HTTP 451 → `HTTP_STUB`, HIGH (delegate to `RknStubPageDetector`)
- [ ] All-OK with `dnsMismatch = false` → `OK`, HIGH; with `dnsMismatch = true` → `OK`, MEDIUM
- [ ] `iterCheckUrls(targets, workers): Flow<RknCheckResult>` — parallel via `Dispatchers.IO` semaphore (default 10 workers, configurable)
- [ ] Cancellation: per-target coroutine cancellation; suite-level cancellation cleans all in-flight
- [ ] Unit tests: each layer's failure path; cascade short-circuits; all-OK happy path; redirect handling; cert CN extraction

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknLayeredProbePipelineTest.kt`:
     - `dns_block_short_circuits_pipeline_no_tcp_attempt()` — fake `SystemDohDnsComparator` returns `DNS_BLOCK`; instrument TCP probe; assert TCP never called; verdict `DNS_BLOCK`; fails until pipeline exists
     - `dns_ok_tcp_reset_returns_tcp_reset_medium()` — DNS OK, TCP throws "Connection reset"; assert `TCP_RESET`, MEDIUM
     - `dns_ok_tcp_ok_tls_reset_returns_tls_block()` — DNS+TCP OK, TLS throws SSL "reset"; assert `TLS_BLOCK`, MEDIUM
     - `dns_tcp_tls_ok_http_stub_marker_returns_http_stub_high()` — earlier layers OK, HTTP body has `доступ ограничен`; assert `HTTP_STUB`, HIGH
     - `http_451_returns_http_stub_high()` — earlier OK, HTTP 451; assert `HTTP_STUB`, HIGH
     - `all_ok_no_dns_mismatch_returns_ok_high()` — assert `OK`, HIGH
     - `all_ok_with_dns_mismatch_returns_ok_medium()` — DNS comparison flagged `DNS_REWRITE` but later layers OK; assert `OK`, MEDIUM
     - `tcp_timeout_returns_timeout_low()` — TCP throws `SocketTimeoutException`; assert `TIMEOUT`, LOW
     - `cert_cn_extracted_on_tls_success()` — fake TLS handshake returns cert with CN `*.example.com`; assert `tlsCertCn == "*.example.com"`
     - `parallel_iter_emits_results_as_completed()` — 5 targets, instrumented with delays; assert flow emits in completion order, not input order
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 10 fail
3. **Implement** — `RknLayeredProbePipeline`, `RknCheckResult`, enums, parallel iter
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract per-layer functions (`probeDns`, `probeTcp`, `probeTls`, `probeHttp`) for readability and unit isolation

## Definition of done

All 10 unit tests green. Pipeline consumed by `add-rkn-control-vs-test-aggregate-verdict` for whitelist + blacklist runs.
