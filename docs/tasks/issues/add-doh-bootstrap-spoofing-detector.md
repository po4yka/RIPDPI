---
title: Add DoH Bootstrap-IP Spoofing Detector via Geoip Cross-Reference
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: [add-dns-integrity-checker, add-geoip-db-and-geosite-db-runtime-loader-and-lookup]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add DoH Bootstrap-IP Spoofing Detector via Geoip Cross-Reference #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `DohBootstrapSpoofingDetector` that, before trusting any DoH endpoint's responses, validates the IP returned by plain DNS for the DoH hostname (e.g. `dns.google`) against the expected provider geoip / org range. Catches the chicken-and-egg attack where an ISP intercepts the bootstrap DNS lookup for the DoH endpoint itself.

## Context

DoH (DNS-over-HTTPS) is widely trusted as a control channel for DNS censorship detection because the *transport* (TLS over HTTPS) can't be inspected by a middlebox without breaking the cert. But there's a subtler attack: **the bootstrap IP for the DoH endpoint is itself resolved via plain DNS**. If the user types `https://dns.google/dns-query`, their stub resolver asks plain DNS for `dns.google`'s IP; the ISP can substitute its own IP at this step, intercept the entire DoH session, and return censored answers — and the user sees what looks like trusted DoH traffic.

dpi-ch's `dns.go` `DnsDohProvider.Filter` field encodes the expected provider geoip via a subnetfilter expression: e.g. `org("google") || as(15169)` for Google DNS. The bootstrap IP is checked against this filter; if the IP doesn't match, the DoH response is flagged as `BOOTSTRAP_SPOOFED` and not used as a control.

**Detection logic per DoH provider:**
1. Resolve `dns.google` (or whatever the configured DoH hostname is) via plain DNS → bootstrap IP
2. Look up bootstrap IP in geoip DB → ASN + org name
3. Match against the provider's expected filter (e.g. `org("google")` or `as(15169)`)
4. If match → bootstrap OK, continue with DoH probes
5. If no match → `BOOTSTRAP_SPOOFED`; record discovered ASN/org for the user; skip this DoH provider as a control

**Provider filters (defaults):**

| DoH endpoint | Filter |
|---|---|
| `dns.google` / `8.8.8.8` | `as(15169) \|\| org("google")` |
| `cloudflare-dns.com` / `1.1.1.1` | `as(13335) \|\| org("cloudflare")` |
| `dns.adguard-dns.com` | `as(212772) \|\| org("adguard")` |
| `dns.quad9.net` | `as(19281) \|\| org("quad9")` |
| `doh.opendns.com` | `as(36692) \|\| org("opendns") \|\| org("cisco")` |
| `dns.nextdns.io` | `as(34939) \|\| org("nextdns")` |

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/checkers/dns.go` `DnsDohProvider.Filter` + `dnsDohMatrix` evaluation

**RIPDPI placement:**
- Detector: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DohBootstrapSpoofingDetector.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DohBootstrapResult.kt`
- Provider config: `core/diagnostics/src/main/assets/dpich/doh_provider_filters.yaml`

## Acceptance criteria

- [ ] `DohBootstrapSpoofingDetector.checkAll(): Map<String, DohBootstrapResult>` — checks every configured DoH provider in parallel
- [ ] `DohBootstrapResult`: `providerName: String`, `dohHostname: String`, `bootstrapIp: String?`, `discoveredAsn: Int?`, `discoveredOrg: String?`, `verdict: BootstrapVerdict (OK | SPOOFED | RESOLVE_FAILED)`, `expectedFilter: String`
- [ ] Bootstrap resolution via system `InetAddress.getAllByName(host)` (NOT via DoH — that defeats the test)
- [ ] Geoip lookup via existing `add-geoip-db-and-geosite-db-runtime-loader-and-lookup` infrastructure
- [ ] Filter evaluation via `add-subnet-filter-dsl`
- [ ] `OK` only when `discoveredAsn / discoveredOrg` matches the provider's filter
- [ ] `SPOOFED` includes the discovered ASN/org in result for user diagnosis ("expected Google AS15169, got AS12389 Rostelecom")
- [ ] `RESOLVE_FAILED` when system can't resolve (no network, NXDOMAIN — distinct from spoofing)
- [ ] Bundled YAML config at `assets/dpich/doh_provider_filters.yaml` with the 6 default providers above; user-override at `filesDir/dpich/doh_provider_filters.yaml`
- [ ] Integration: `add-dns-integrity-checker` consumes this — DoH providers flagged `SPOOFED` are excluded from the integrity-check control
- [ ] Unit tests: spoofed bootstrap IP detected; legitimate IP passes; resolve failure distinguished

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DohBootstrapSpoofingDetectorTest.kt`:
     - `legitimate_google_doh_ip_returns_ok()` — fake system resolver returns `8.8.8.8`; geoip maps to AS15169 Google; filter `as(15169)`; assert `OK`; fails until detector exists
     - `spoofed_bootstrap_ip_returns_spoofed_with_discovered_asn()` — fake system returns `1.2.3.4`; geoip maps to AS12389 Rostelecom; assert `SPOOFED`, `discoveredAsn = 12389`, `discoveredOrg = "Rostelecom"`
     - `org_substring_filter_matches()` — fake geoip org `"Cloudflare, Inc."`; filter `org("cloudflare")`; assert `OK`
     - `resolve_failure_returns_resolve_failed_not_spoofed()` — fake system throws `UnknownHostException`; assert `RESOLVE_FAILED`, distinct from `SPOOFED`
     - `parallel_checks_all_providers()` — instrument; assert one resolve+geoip lookup per configured provider
     - `dns_integrity_checker_excludes_spoofed_providers()` — fake spoofed Google; verify integrity check uses only Cloudflare/Quad9/etc.
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 6 fail
3. **Implement** — `DohBootstrapSpoofingDetector`, YAML config loader, integration with DNS integrity checker
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract bootstrap-resolution-without-doh into a private `resolveSystemOnly(host)` helper documented as "MUST NOT use DoH"

## Definition of done

All 6 unit tests green. Bootstrap status surfaced in DNS integrity card with per-provider trust badges. Spoofed providers excluded from integrity-check control set automatically.
