---
title: Add Bring-Your-Own-Host TCP 16-20 Path Compatibility Checker
type: task
status: doing
area: diagnostics
priority: low
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-11
---

- [ ] #task Add Bring-Your-Own-Host TCP 16-20 Path Compatibility Checker #repo/RIPDPI #area/diagnostics #status/doing 🔽

## Objective

Add `ByohDomainCompatibilityChecker` that, given a user-controlled HTTPS server `<dst-ip>` serving a >=128KB static file at a known URL path, tests a list of input domains by issuing `curl -k --resolve <domain>:443:<dst-ip>` style downloads and reports which domains successfully transferred >=`range_to` bytes on the observed TCP 16-20 path.

## Context

Android port of dpi-checkers' TCP 16-20 domain-compatibility implementation. The premise: TCP 16-20 middleboxes may handle caller-supplied domain names differently. When the SNI, and possibly the HTTP `Host` header, matches an operator-specific rule, the 16-20KB byte-counting connection reset is not observed. Recording that behavior is useful because:
1. The diagnostic records whether caller-supplied SNI values correlate with TCP 16-20 path outcomes
2. Operator-specific host handling varies, so a per-user diagnostic is more accurate than a static fixture

**Why bring-your-own-host:** the test needs a server that:
1. Is on the target network path where TCP 16-20 behavior is present
2. Serves the same content regardless of SNI while testing caller-supplied SNI values
3. Returns a file >=128KB so the request crosses the observed 16-20KB middlebox threshold

These constraints can't be met by a public test endpoint; they require user-provided infrastructure with the server properties above.

**Workflow:**
1. User provides: `dstIp: String`, `urlPath: String` (default `/1MB.bin`), `domainList: List<String>` (or use a synthetic bundled fixture for preview/testing)
2. For each domain, issue HTTPS request with SNI = domain, `Host: <domain>`, target IP = `dstIp`
3. Read response body up to `rangeTo` bytes (default 65535 — well past the 16-20KB threshold)
4. If transferred >= `rangeTo` bytes -> domain is compatible with the observed TCP 16-20 path
5. Otherwise -> domain is incompatible with the observed TCP 16-20 path
6. Output: list of compatible domains with optional ISP/country attribution via existing reverse-geoip support

**Synthetic fixture:** a small synthetic domain fixture can be bundled for regression tests and UI preview. Real compatibility results remain user-generated from an explicitly configured BYOH endpoint.

**Reference:** local dpi-checkers TCP 16-20 domain-compatibility implementation.

**RIPDPI placement:**
- Checker: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/ByohDomainCompatibilityChecker.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/ByohCompatibilityResult.kt`
- Synthetic bundled fixture: `core/diagnostics/src/main/assets/dpich/synthetic_compatibility_fixture.txt`
- Sample input list: `core/diagnostics/src/main/assets/dpich/synthetic_input_domains.txt`

## Acceptance criteria

- [ ] `ByohDomainCompatibilityChecker.run(config: ByohConfig): Flow<ByohProgress>`
- [ ] `ByohConfig`: `dstIp: String` (required), `urlPath: String = "/1MB.bin"`, `domainList: List<String>` (or asset name), `timeoutMs: Long = 5000`, `rangeTo: Int = 65535`, `concurrency: Int = 1` (default single-threaded matching original; configurable)
- [ ] `ByohProgress` sealed: `Probing(domain, idx, total)`, `Compatible(domain)`, `Incompatible(domain, reason)`, `Completed(stats)`
- [ ] Per-domain probe: `OkHttpClient` with custom DNS override → `dstIp`; SNI via `setEndpoint(InetSocketAddress(dstIp, 443))`; `Host: <domain>` header; reads body up to `rangeTo` bytes
- [ ] Compatibility verdict: bytes received >= `rangeTo` -> compatible
- [ ] Hostname verifier disabled (self-signed cert on user's server is the documented setup)
- [ ] Cancellable: user can stop mid-run; partial results retained
- [ ] Output writable to file via `add-detection-export-share` (CSV: `domain,compatible,bytes_received`)
- [ ] Bundled synthetic fixture accessible via separate UI action ("View synthetic fixture" — reads `synthetic_compatibility_fixture.txt`); does not require BYOH server
- [ ] Settings UI: `dstIp` and `urlPath` inputs; "Use synthetic preview data" toggle
- [ ] BYOH config screen includes a neutral local summary of server requirements without external setup links
- [ ] Unit tests: per-domain probe with `MockWebServer`; compatible vs incompatible verdict; cancellation retains results

## TDD workflow

1. **Write tests first**:
  - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/ByohDomainCompatibilityCheckerTest.kt`:
     - `domain_with_full_transfer_classified_compatible()` — `MockWebServer` returns 200 + 65535 bytes for SNI vk.com; assert `Compatible("vk.com")`; fails until checker exists
     - `domain_with_partial_transfer_classified_incompatible()` — server returns RST after 8KB; assert `Incompatible("incomplete.example", reason includes "8192")`
     - `dst_ip_routing_used_not_real_dns()` — instrument; assert TCP connect target == config `dstIp` even though SNI is different
     - `host_header_matches_sni()` — capture request; assert `Host` header equals SNI value
     - `cancellation_mid_list_retains_results()` — cancel after 5/100 domains; assert 5 results retained
     - `csv_export_includes_bytes_received()` — assert each row has `domain,compatible,bytes_received` columns
     - `synthetic_fixture_accessible_without_dst_ip()` — call `loadSyntheticFixture()`; assert returns bundled synthetic entries
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 7 fail
3. **Implement** — `ByohDomainCompatibilityChecker`, asset loader extension, settings screen
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract per-domain probe into `probeOneDomain(domain, dstIp): ByohProbeResult`

## Definition of done

All 7 unit tests green. BYOH config + run UI accessible from DiagnosticsScreen Tools section behind an "Advanced — requires user-controlled server" disclosure. Synthetic fixture accessible without setup. Neutral server requirements summary in place.
