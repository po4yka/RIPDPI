---
title: Add Bring-Your-Own-Host TCP 16-20 Domain Whitelist Checker
type: task
status: backlog
area: diagnostics
priority: low
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: [add-tcp16-fat-header-dpi-probe]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Bring-Your-Own-Host TCP 16-20 Domain Whitelist Checker #repo/RIPDPI #area/diagnostics #status/backlog 🔽

## Objective

Add `ByohDomainWhitelistChecker` that, given a user-controlled HTTPS server `<dst-ip>` serving a ≥128KB static file at a known URL path, brute-forces a list of input domains by issuing `curl -k --resolve <domain>:443:<dst-ip>` style downloads and reports which domains successfully transferred ≥`range_to` bytes (those are on the censor's TCP 16-20 whitelist).

## Context

Android port of dpi-checkers' `tcp-16-20_dwc/domain_whitelist_checker.py`. The premise: TCP 16-20 censors (TSPU) typically maintain a domain whitelist — when the SNI (and possibly the HTTP `Host` header) matches a whitelisted entry, the 16-20KB byte-counting RST is suppressed. Discovering the user's local ISP's whitelist is operationally useful because:
1. Setting a VPN client's `--sni` override to a discovered whitelisted domain bypasses TCP 16-20 blocks
2. The whitelist varies between operators; a per-user discovery is more accurate than a published list

**Why bring-your-own-host:** the test needs a server that:
1. Is in a censored network (so TSPU/TCP 16-20 is on the path)
2. Serves the same content regardless of SNI (because we're spoofing arbitrary SNIs)
3. Returns a file ≥128KB so the request crosses the TSPU's 16-20KB threshold and triggers the byte-counter

These constraints can't be met by a public test endpoint — they require user infrastructure. dpi-checkers' README documents a minimal nginx config that satisfies all three.

**Workflow:**
1. User provides: `dstIp: String`, `urlPath: String` (default `/1MB.bin`), `domainList: List<String>` (or use the bundled OpenDNS-top-10K subset)
2. For each domain, issue HTTPS request with SNI = domain, `Host: <domain>`, target IP = `dstIp`
3. Read response body up to `rangeTo` bytes (default 65535 — well past the 16-20KB threshold)
4. If transferred ≥ `rangeTo` bytes → domain is whitelisted (TCP 16-20 suppressed)
5. Otherwise → domain is blocked (TCP 16-20 active)
6. Output: list of whitelisted domains with optional ISP/country attribution via existing `add-rkn-system-doh-dns-comparison` reverse-geoip

**Pre-computed result:** the dpi-checkers repo ships a 2025-07-02 result file (`results/based_on_opendns_2025-07-02.txt`) with 266 whitelisted domains out of 10,000 OpenDNS top. This can be bundled as a starting point for users who don't run the live check.

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/tcp-16-20_dwc/README.md` + `domain_whitelist_checker.py`

**RIPDPI placement:**
- Checker: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/ByohDomainWhitelistChecker.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/ByohWhitelistResult.kt`
- Pre-computed bundled list: `core/diagnostics/src/main/assets/dpich/whitelisted_domains_opendns_2025-07-02.txt`
- Sample input list: `core/diagnostics/src/main/assets/dpich/opendns_top_10k_subset.txt` (subset, ~2000 entries — full 10K too large for APK)

## Acceptance criteria

- [ ] `ByohDomainWhitelistChecker.run(config: ByohConfig): Flow<ByohProgress>`
- [ ] `ByohConfig`: `dstIp: String` (required), `urlPath: String = "/1MB.bin"`, `domainList: List<String>` (or asset name), `timeoutMs: Long = 5000`, `rangeTo: Int = 65535`, `concurrency: Int = 1` (default single-threaded matching original; configurable)
- [ ] `ByohProgress` sealed: `Probing(domain, idx, total)`, `Whitelisted(domain)`, `Blocked(domain, reason)`, `Completed(stats)`
- [ ] Per-domain probe: `OkHttpClient` with custom DNS override → `dstIp`; SNI via `setEndpoint(InetSocketAddress(dstIp, 443))`; `Host: <domain>` header; reads body up to `rangeTo` bytes
- [ ] Whitelist verdict: bytes received >= `rangeTo` → whitelisted
- [ ] Hostname verifier disabled (self-signed cert on user's server is the documented setup)
- [ ] Cancellable: user can stop mid-run; partial results retained
- [ ] Output writable to file via `add-detection-export-share` (CSV: `domain,whitelisted,bytes_received`)
- [ ] Bundled pre-computed result accessible via separate UI action ("View pre-computed list" — reads `whitelisted_domains_opendns_2025-07-02.txt`); does not require BYOH server
- [ ] Settings UI: `dstIp` and `urlPath` inputs; "Skip live check, use bundled list only" toggle
- [ ] Documentation deep-link to dpi-checkers nginx setup recipe in the BYOH config screen
- [ ] Unit tests: per-domain probe with `MockWebServer`; whitelisted vs blocked verdict; cancellation retains results

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/ByohDomainWhitelistCheckerTest.kt`:
     - `domain_with_full_transfer_classified_whitelisted()` — `MockWebServer` returns 200 + 65535 bytes for SNI vk.com; assert `Whitelisted("vk.com")`; fails until checker exists
     - `domain_with_partial_transfer_classified_blocked()` — server returns RST after 8KB; assert `Blocked("blocked.example", reason includes "8192")`
     - `dst_ip_routing_used_not_real_dns()` — instrument; assert TCP connect target == config `dstIp` even though SNI is different
     - `host_header_matches_sni()` — capture request; assert `Host` header equals SNI value
     - `cancellation_mid_list_retains_results()` — cancel after 5/100 domains; assert 5 results retained
     - `csv_export_includes_bytes_received()` — assert each row has `domain,whitelisted,bytes_received` columns
     - `prebundled_list_accessible_without_dst_ip()` — call `loadPrebundledResults()`; assert returns 266 entries from OpenDNS-2025-07-02 fixture
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 7 fail
3. **Implement** — `ByohDomainWhitelistChecker`, asset loader extension, settings screen
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract per-domain probe into `probeOneDomain(domain, dstIp): ByohProbeResult`

## Definition of done

All 7 unit tests green. BYOH config + run UI accessible from DiagnosticsScreen Tools section behind an "Advanced — requires user-controlled server" disclosure. Pre-computed list accessible without setup. Documentation deep-link in place.
