---
title: Add Domain Reachability Scanner with TLS 1.3/1.2/HTTP Stage Tracking
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: [add-dpi-error-classifier, add-ip-fake-ip-classifier, add-dpi-target-assets, add-dns-integrity-checker]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Domain Reachability Scanner with TLS 1.3/1.2/HTTP Stage Tracking #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `DomainReachabilityScanner` that probes each blocked test domain three times (TLS 1.3, TLS 1.2, plain HTTP) with stage-tracked error classification, ISP page detection via stub IP cross-reference, and TCP16-band timeout detection.

## Context

Android port of dpi-detector's Test 3 (`check_domain_tls` + `check_http_injection`). Each domain is probed via three independent attempts with different protocol pinning: TLS 1.3 only, TLS 1.2 only, and plain HTTP. Per-attempt the scanner tracks the connection stage (`TCP_CONNECT` → `TLS_HANDSHAKE` → `SENDING_DATA` → `READING_DATA`) so that the error classifier can distinguish between, for example, TCP RST during handshake (DPI active rejection) vs after data transfer (post-TLS injection).

**Per-attempt flow:**
1. Resolve domain → if `resolvedIp ∈ stubIps` (from DNS integrity checker), early-exit with `ISP_PAGE`
2. If `resolvedIp` matches Fake-IP (`198.18/15`) → return `FAKE_IP`
3. Otherwise: build `OkHttpClient` with `SSLContext` pinned to TLS 1.3 / 1.2 (or plain socket for HTTP), attach `OkHttpProbeEventListener` for stage tracking
4. Issue `GET https://<domain>` with `Accept-Encoding: identity`, `Connection: close`, no follow-redirects
5. Classify result: HTTP 451 → `BLOCKED`; redirect to same/sub-domain → `REDIR_OK`; redirect to foreign domain → `REDIR_SUSPICIOUS`; status 200-499 → `OK`; exception → run through `DpiErrorClassifier` with current stage
6. Special case: read timeout where bytes read ∈ [16KB, 20KB] → `TCP16_BAND_TIMEOUT`

**TLS version pinning:** `SSLContext.getInstance("TLSv1.3")` / `"TLSv1.2"` with `SSLParameters.protocols` set to a single-element array. This forces JSSE to negotiate exactly that version so the scanner can detect TLS-version-specific blocks (some Russian DPI blocks TLS 1.2 but allows 1.3, or vice-versa).

**Plain HTTP probe:** uses `HEAD` with `Host: <domain>` header to detect HTTP-injection (e.g., 451-with-fake-page).

**Reference:** `/Users/po4yka/GitRep/dpi-detector/core/tls_scanner.py` — `check_domain_tls`, `check_http_injection`, `_check_tls_single`

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DomainReachabilityScanner.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DomainReachabilityResult.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/TlsVersionPinner.kt`

**Network security config:** Android 9+ blocks cleartext HTTP. The HTTP probe needs a `network_security_config.xml` entry whitelisting cleartext for the 40 test domains, or wrap the probe in a custom `Socket` that bypasses platform restrictions (preferred — keeps app-level config clean).

## Acceptance criteria

- [ ] `DomainReachabilityResult`: per-domain `tls13: AttemptResult`, `tls12: AttemptResult`, `http: AttemptResult`, plus aggregated `verdict: DomainVerdict (OK | BLOCKED | TLS_VERSION_BLOCK | ISP_PAGE | TCP16_BAND | DNS_FAIL | UNREACHABLE)`
- [ ] `AttemptResult`: `status`, `detail`, `bytesRead`, `latencyMs`, `stage` (last reached), `error: DpiProbeError?`
- [ ] TLS 1.3 / 1.2 pinning via `SSLContext` + `SSLParameters.protocols` single-element array
- [ ] Stage tracking via `OkHttpProbeEventListener` from `add-dpi-error-classifier`
- [ ] Stub IP early-exit: if resolved IP ∈ `stubIps` set passed in from DNS integrity checker → `ISP_PAGE` without TLS attempt
- [ ] Fake-IP early-exit: if resolved IP ∈ `198.18.0.0/15` → `FAKE_IP` without TLS attempt
- [ ] Redirect classification: same-domain or sub-domain → `REDIR_OK`; foreign domain → `REDIR_SUSPICIOUS`; redirect to stub-IP target → `ISP_PAGE`
- [ ] TCP16-band: read timeout with bytes read between 16384 and 20480 → `TCP16_BAND_TIMEOUT`
- [ ] HTTP probe via `HEAD` with explicit `Host` header; cleartext permitted via custom Socket bypass (no app-wide network security config relaxation)
- [ ] All probes run via shared `Semaphore(MAX_CONCURRENT)`; per-attempt timeout configurable
- [ ] Unit tests with `MockWebServer` (OkHttp) for redirect, 451, timeout cases

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DomainReachabilityScannerTest.kt`:
     - `tls13_ok_returns_ok_attempt()` — `MockWebServer` returns 200 over TLS 1.3; assert `tls13.status == OK`; fails until scanner exists
     - `http_451_returns_blocked()` — server returns 451; assert `BLOCKED`
     - `redirect_to_same_domain_returns_redir_ok()` — server returns 301 with `Location: https://example.com/foo`; assert `REDIR_OK`
     - `redirect_to_foreign_domain_returns_redir_suspicious()` — server returns 301 with `Location: https://block.gov/foo`; assert `REDIR_SUSPICIOUS`
     - `stub_ip_short_circuits_to_isp_page()` — inject `stubIps = setOf("100.64.0.5")`; resolve mock returns `100.64.0.5`; assert `ISP_PAGE` without TLS attempt
     - `fake_ip_short_circuits_to_fake_ip()` — resolve mock returns `198.18.0.1`; assert `FAKE_IP`
     - `tcp16_band_timeout_classified()` — mock socket reads 17000 bytes then stalls; assert `TCP16_BAND_TIMEOUT`
     - `tls12_only_block_detected_when_tls13_succeeds()` — TLS 1.3 attempt succeeds, TLS 1.2 attempt RSTs; assert `verdict == TLS_VERSION_BLOCK`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `DomainReachabilityScanner`, `TlsVersionPinner`, result models, cleartext-Socket bypass
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract per-attempt logic into a private `runSingleAttempt(domain, protocol)`; share `OkHttpClient` builder with TCP16 probe

## Definition of done

All 8 unit tests green. `DomainReachabilityScanner` surfaced in DiagnosticsScreen Tools section with per-domain × per-attempt result table. Receives `stubIps` from `DnsIntegrityChecker` via DI.
