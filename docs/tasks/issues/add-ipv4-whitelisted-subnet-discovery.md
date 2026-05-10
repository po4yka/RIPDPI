---
title: Add IPv4 Whitelisted Subnet Discovery via RIPE Stat API
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: [add-webhost-farm-dynamic-host-discovery]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add IPv4 Whitelisted Subnet Discovery via RIPE Stat API #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `Ipv4WhitelistedSubnetDiscoverer` that, given a list of suspect ASNs (Yandex/VK/EdgeCenter by default), fetches their announced /24 prefixes from RIPE Stat, samples N hosts per /24, sends HEAD :443 to each, and reports which /24 subnets are on the censor's CIDR whitelist (≥ alive_min hosts respond).

## Context

This is the Android port of the `ipv4-whitelisted-subnets/main.js` web checker. The premise: when a censor uses CIDR whitelisting (per `add-cidr-whitelist-detector`), the **specific subnets** on the whitelist are themselves valuable diagnostic data. Knowing which /24s in Yandex's announced space are whitelisted lets the user route bypass traffic through those subnets specifically.

**Algorithm:**
1. For each suspect ASN, fetch announced prefixes from RIPE Stat: `https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS<asn>`
2. Filter to IPv4 /24 prefixes (configurable, default `only_24_prefix = true`)
3. Cache results in `filesDir/dpich/whitelisted_subnets_cache.json` (RIPE Stat is rate-limited)
4. For each /24, generate `sn_sample_size` random IPs (default 25) within the /24
5. Send HTTP HEAD to `https://<ip>:443` (no SNI matters because we're testing IP reachability, not domain)
6. Count alive hosts (any HTTP response, including TLS errors after handshake start, counts as "alive")
7. If alive count ≥ `sn_alive_min` (default 3), the /24 is whitelisted

**Cache mechanism (matches web checker UX):**
- "Cache subnets" action — fetches RIPE Stat data; only run on a known-non-whitelisted network (so RIPE Stat is reachable)
- "Check subnets" action — uses cached subnets, runs the alive sampling
- "Save" action — exports CSV: `<provider>,<cidr>,<alive_count>,<verdict>`

**Default ASN list (verbatim from `TEST_SUITE`):**
- Yandex: 13238, 44534, 200350, 202611, 208398, 208795, 210656, 212066, 215013, 215109
- VK: 28709, 47541, 47542, 47764, 60863, 62243, 199295, 207581
- EdgeCenter: 201589, 207059, 210756

**Performance budget:** dpi-checkers' web README warns "several tens of minutes" worst case. Android impl should respect that — explicit user-initiated, with progress emit per /24, cancellable.

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/ipv4-whitelisted-subnets/main.js` (full file)

**RIPDPI placement:**
- Discoverer: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/Ipv4WhitelistedSubnetDiscoverer.kt`
- RIPE Stat client: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/RipeStatClient.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/WhitelistedSubnetResult.kt`
- Cache: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/SubnetsCache.kt`
- Bundled ASN suite: `core/diagnostics/src/main/assets/dpich/ipv4_whitelist_asns.json`

## Acceptance criteria

- [ ] `Ipv4WhitelistedSubnetDiscoverer.cacheSubnets(): Flow<CacheProgress>` — fetches RIPE Stat for all configured ASNs; emits per-ASN progress; writes to `filesDir/dpich/whitelisted_subnets_cache.json`
- [ ] `Ipv4WhitelistedSubnetDiscoverer.checkCachedSubnets(config): Flow<SubnetCheckProgress>` — runs alive sampling against cached subnets; emits per-subnet progress
- [ ] `WhitelistedSubnetResult`: `provider: String`, `cidr: String`, `aliveCount: Int`, `aliveSampled: Int`, `whitelisted: Boolean`
- [ ] Config: `timeoutMs (default 5000)`, `subnetSampleSize (default 25)`, `subnetAliveMin (default 3)`, `only24Prefix (default true)`
- [ ] RIPE Stat: HTTP GET to `https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS<asn>`; parses `data.prefixes[].prefix`
- [ ] Sample IP generation: random `Int` in subnet range, masked to /24; uniform distribution; no duplicates within a single subnet's sample
- [ ] HEAD probe via `OkHttpClient` with `redirect = MANUAL`; "alive" = any response (incl. TLS error post-handshake-start) counts
- [ ] Cache persisted; subsequent `checkCachedSubnets()` calls do not refetch RIPE Stat
- [ ] CSV export: `provider,cidr,alive_count,whitelisted` rows; persisted via `add-detection-export-share` if available
- [ ] Default ASN suite bundled at `assets/dpich/ipv4_whitelist_asns.json` matching the 3-provider list above
- [ ] Cancellable: `Flow` consumer can cancel mid-run; partial results retained
- [ ] Unit tests: RIPE Stat parsing; sample IP generation in /24; alive threshold logic; cache read/write

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/Ipv4WhitelistedSubnetDiscovererTest.kt`:
     - `cache_fetches_all_configured_asns_from_ripe()` — mock RIPE Stat; assert one fetch per ASN; fails until discoverer exists
     - `check_skips_subnet_below_alive_min()` — fake 25-host sample, only 2 respond; assert `whitelisted = false`
     - `check_marks_subnet_whitelisted_at_or_above_alive_min()` — 3/25 respond; assert `whitelisted = true`
     - `only_24_prefix_filter_excludes_larger_subnets()` — RIPE returns mix of /22 + /24; assert only /24 evaluated
     - `cache_persisted_across_runs()` — call `cacheSubnets()`; second call to `checkCachedSubnets()` does not hit RIPE Stat
     - `tls_error_post_handshake_counts_as_alive()` — mock returns RST after TLS started; assert counted as alive
     - `cancellation_retains_partial_results()` — cancel after 5/100 subnets; assert 5 results retained
     - `csv_export_format_matches_spec()` — assert each row is `<provider>,<cidr>,<alive>,<bool>`
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/RipeStatClientTest.kt`:
     - `parses_announced_prefixes_from_json()` — feed RIPE response JSON fixture; assert correct list of prefixes
     - `handles_rate_limit_429_with_backoff()` — mock returns 429; assert single retry after backoff; failure surfaces as exception with rate-limit context
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 10 fail
3. **Implement** — `Ipv4WhitelistedSubnetDiscoverer`, `RipeStatClient`, `SubnetsCache`, asset bundle
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract `randomIpInSubnet(cidr): String` into a network util shared with `WebhostFarm`

## Definition of done

All 10 unit tests green. Discovery surfaced in DiagnosticsScreen with Cache/Check/Save actions matching the web checker UX. CSV export integrated with existing share flow.
