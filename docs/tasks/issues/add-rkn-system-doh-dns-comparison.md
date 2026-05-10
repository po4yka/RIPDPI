---
title: Add Set-Based System Resolver vs DoH DNS Comparison
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: rkn-block-checker-parity-epic
blocks: [add-rkn-layered-probe-pipeline]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Set-Based System Resolver vs DoH DNS Comparison #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `SystemDohDnsComparator` that resolves a hostname twice — once via Android's system `InetAddress.getAllByName` and once via Cloudflare DoH JSON — collects the **full sets** of returned IPv4 addresses, and reports `DNS_BLOCK`, `DNS_REWRITE`, or `OK` based on set disjointness, not first-IP comparison.

## Context

This is the Android port of `rkn_checker/dns.py` and the DNS branch of `core.check_url`. Distinct from `add-dns-integrity-checker` (which compares **wire UDP** to DoH to detect interception of port 53):

- `add-dns-integrity-checker`: catches ISPs that intercept and rewrite UDP/53 traffic → answers "is the wire DNS protocol being interfered with?"
- This task: catches ISPs that rewrite the **system resolver path** itself (poisoned local resolver, DHCP-pushed hostile DNS, transparent rewriting at the gateway) → answers "does the resolver my apps actually use return the wrong answer?"

The two signals are complementary. A user with Private DNS configured may pass `add-dns-integrity-checker` (wire DNS unaffected) but fail this comparison if their Private DNS provider itself returns censored answers.

**Critical correctness rule (the bug rkn-block-checker fixed in #5):** large sites return multiple A-records and rotate the order on every query (load balancing). Comparing first-IP-only produces false positives on ~50% of runs. The fix is **set comparison with disjointness check**: only flag as poisoning when the two sets share **zero** addresses. Any shared address → no flag (load balancing, not censorship).

**Verdicts:**
- system fails AND DoH succeeds → `DNS_BLOCK`, confidence `HIGH` (DoH is the trusted control)
- system fails AND DoH fails → `DOWN`, confidence `LOW` (could be NXDOMAIN, dead authoritative, or DNS-level block — can't distinguish without more signals)
- system succeeds AND DoH fails → `OK` (with note: "DoH lookup failed — control comparison unavailable, DNS poisoning cannot be ruled out")
- both succeed AND sets disjoint → `DNS_REWRITE`, confidence `MEDIUM` (note: "transparent DNS rewriting; system returned IPs not seen by DoH")
- both succeed AND any shared IP → `OK` (load balancing — no flag)

**Android nuance:** `InetAddress.getAllByName` honors Private DNS settings. The result struct should include `dnsMethod: SYSTEM | PRIVATE_DNS_DOT | PRIVATE_DNS_DOH` (queryable via `LinkProperties.privateDnsServerName` on API 28+) so users understand whether the "system side" is the ISP resolver or a user-chosen alternative.

**Reference:** `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/dns.py` (full file) + `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/core.py` lines 40-78

**RIPDPI placement:**
- Comparator: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/SystemDohDnsComparator.kt`
- DoH client: reuses `DohJsonResolver` from `add-doh-json-api-resolver-path-alongside-rfc-8484-wire`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/DnsComparisonResult.kt`

## Acceptance criteria

- [ ] `SystemDohDnsComparator.compare(host: String): DnsComparisonResult`
- [ ] System side: `InetAddress.getAllByName(host)` filtered to IPv4 (`Inet4Address`); wrapped in `withContext(Dispatchers.IO)` with timeout
- [ ] DoH side: HTTP GET to `https://cloudflare-dns.com/dns-query?name=<host>&type=A` with `Accept: application/dns-json`; parse `Answer[].data` for `type == 1`
- [ ] Result sets stored as `Set<String>` (not `List`) — explicit set semantics
- [ ] Comparison logic: **disjointness only** — `if (sysSet.isNotEmpty() && dohSet.isNotEmpty() && sysSet.intersect(dohSet).isEmpty()) → DNS_REWRITE`
- [ ] First-IP fields preserved for backward compat: `sysIp = sysSet.sorted().firstOrNull()`, `dohIp = dohSet.sorted().firstOrNull()`
- [ ] `DnsComparisonResult`: `verdict (OK | DNS_BLOCK | DNS_REWRITE | DOWN)`, `confidence`, `sysIps: Set<String>`, `dohIps: Set<String>`, `sysIp: String?`, `dohIp: String?`, `dnsMethod (SYSTEM | PRIVATE_DNS_DOT | PRIVATE_DNS_DOH)`, `notes: List<String>`
- [ ] DoH timeout configurable, default 5s; system resolver timeout 5s (wraps `getAllByName` in `withTimeout`)
- [ ] Proxy-aware: if a SOCKS/HTTP proxy is configured for diagnostic probes (from `add-upstream-http-and-socks5-proxy-override-for-diagnostic-probes`), DoH goes through proxy but **system resolver does not** — comparing system-via-proxy to DoH-via-proxy defeats the test
- [ ] Unit tests with mock `Resolver` (system) + `MockWebServer` (DoH)

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/SystemDohDnsComparatorTest.kt`:
     - `system_fails_doh_succeeds_returns_dns_block_high()` — fake system throws `UnknownHostException`, DoH returns `["1.2.3.4"]`; assert `DNS_BLOCK`, `HIGH`; fails until comparator exists
     - `both_fail_returns_down_low()` — system throws, DoH returns empty; assert `DOWN`, `LOW`
     - `system_succeeds_doh_fails_returns_ok_with_note()` — system returns `["1.2.3.4"]`, DoH returns empty; assert `OK` with note "DoH lookup failed"
     - `disjoint_sets_returns_dns_rewrite_medium()` — system `["1.2.3.4"]`, DoH `["5.6.7.8"]`; assert `DNS_REWRITE`, `MEDIUM`
     - `shared_ip_means_no_flag_even_if_other_ips_differ()` — system `["1.2.3.4", "9.9.9.9"]`, DoH `["1.2.3.4", "5.6.7.8"]`; assert `OK` (intersection non-empty)
     - `multi_a_record_load_balancing_not_flagged()` — system `["a", "b", "c"]` rotated to `["c", "a", "b"]` by DoH; assert `OK`
     - `first_ip_field_is_lowest_sorted()` — `sysSet = ["9.9.9.9", "1.1.1.1"]`; assert `sysIp == "1.1.1.1"`
     - `proxy_routes_doh_but_not_system_resolver()` — instrument both calls; assert system call did NOT use proxy, DoH call DID use proxy
     - `private_dns_method_detected_when_configured()` — fake `LinkProperties.privateDnsServerName = "1.1.1.1"`; assert `dnsMethod == PRIVATE_DNS_DOT`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 9 fail
3. **Implement** — `SystemDohDnsComparator`, result model, Private DNS method detection
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract `intersect()` set check into a documented private function with the false-positive rationale as a comment

## Definition of done

All 9 unit tests green. `SystemDohDnsComparator` consumed by `add-rkn-layered-probe-pipeline`'s DNS layer. Private DNS method correctly identified and surfaced.
