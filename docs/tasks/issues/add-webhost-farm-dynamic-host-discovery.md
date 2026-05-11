---
title: Add Webhost-Farm Dynamic Host Discovery from Filtered Subnets
type: task
status: doing
area: diagnostics
priority: high
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: [add-cidr-whitelist-detector, add-ipv4-whitelisted-subnet-discovery]
blocked_by: [add-utls-diagnostic-probe-clienthello-fingerprinting]
created: 2026-05-10
updated: 2026-05-11
---

- [ ] #task Add Webhost-Farm Dynamic Host Discovery from Filtered Subnets #repo/RIPDPI #area/diagnostics #status/doing ⏫

## Objective

Add `WebhostFarm` that, given a `Set<IpRange>` from `SubnetFilterDsl` plus a target count `N`, parallel-probes random IPs from the union of those ranges, filters down to those that complete a TCP+TLS handshake within configured timeouts, and returns up to `N` confirmed-alive hosts ready for downstream probes.

## Context

dpi-ch's `webhostfarm` is the bridge between the subnet-filter DSL and the actual probes. It solves three problems at once:

1. **Defeating censor whitelisting** — picks fresh random IPs per run; the censor can't preemptively whitelist them
2. **Reducing probe target load** — each user hits different hosts, distributing diagnostic traffic
3. **Filtering dead targets out** — guarantees downstream probes get only IPs that respond to TCP+TLS

The discovery loop:

```
input: subnets: Set<IpRange>, count: N, port: 443, sni: "example.com"
1. Flatten subnets to a virtual list of all candidate IPs (or sample if too many)
2. Shuffle deterministically (seeded with a per-run nonce)
3. In parallel up to `workers`:
   - Pop next candidate IP
   - Try TCP connect to ip:port (timeout configurable)
   - If TCP OK, try TLS handshake with SNI (uTLS-equivalent, Chrome fingerprint)
   - If TLS OK, append to alive[]
4. Stop when alive.size == N OR no more candidates
output: alive[]: List<DiscoveredHost>
```

**Why uTLS-equivalent:** dpi-ch uses `refraction-networking/utls` to emit a Chrome ClientHello. Without this, the censor can fingerprint the diagnostic tool's own ClientHello signature and decide to let those handshakes through (false-negative the test). RIPDPI's transport already includes `pin-utls-to-v1-8-2-...` — the diagnostic probes must reuse the same code path.

**Output shape:** each `DiscoveredHost` carries `ip`, `port`, the resolving subnet, the AS number/org name (for result-grouping in UI), and the per-host TCP/TLS handshake timings.

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/webhostfarm/` (Go source) + dpi-ch docs `Killer features` → `The era of dynamic`

**RIPDPI placement:**
- Farm: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/WebhostFarm.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/DiscoveredHost.kt`

## Acceptance criteria

- [ ] `WebhostFarm.discover(subnets: Set<IpRange>, count: Int, port: Int = 443, sni: String? = null, workers: Int = 16): List<DiscoveredHost>`
- [ ] `DiscoveredHost`: `ip: String`, `port: Int`, `sourceSubnet: IpRange`, `asn: Int?`, `org: String?`, `tcpTimeMs: Long`, `tlsTimeMs: Long`
- [ ] Candidate sampling: if total IPs in `subnets` > `MAX_CANDIDATES (default 10_000)`, sample uniformly without replacement to that cap to bound memory
- [ ] Random shuffle seeded with per-run nonce so results are reproducible per run but distinct across runs
- [ ] TCP+TLS probe via `add-utls-diagnostic-probe-clienthello-fingerprinting` client; falls back to system SSLSocket if uTLS-equivalent unavailable (with note in result)
- [ ] Configurable timeouts via constructor: `tcpConnectTimeout (default 3s)`, `tlsHandshakeTimeout (default 5s)`
- [ ] Worker pool via coroutine `Semaphore(workers)`
- [ ] Early stop when `alive.size == count` — pending probes cancelled
- [ ] Returns at most `count` hosts; empty list if no candidates respond
- [ ] Reverse-geoip lookup attached per host (asn + org from existing geoip DB)
- [ ] Unit tests: mock TCP/TLS client; assert sampling cap; early-stop; reverse-geoip enrichment

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/WebhostFarmTest.kt`:
     - `returns_n_alive_hosts_when_more_candidates_than_n()` — fake 100 candidates all alive; `count = 10`; assert exactly 10 returned; fails until farm exists
     - `returns_fewer_than_n_when_not_enough_alive()` — fake 100 candidates only 3 alive; `count = 10`; assert size 3
     - `early_stops_after_n_alive_found()` — instrument probe count; fake 100 candidates all alive; `count = 5`; assert ≤ 5 + workers probes attempted (allowing in-flight)
     - `tcp_failure_excludes_host()` — TCP fails for IP X; assert X not in result
     - `tls_failure_excludes_host_even_if_tcp_ok()` — TCP OK, TLS fails; assert excluded
     - `large_subnet_set_capped_at_max_candidates()` — feed subnet covering 1M IPs; instrument candidate iterator; assert ≤ 10_000 candidates evaluated
     - `geoip_enrichment_populates_asn_and_org()` — fake geoip; assert each `DiscoveredHost.asn` and `org` populated
     - `seeded_random_produces_reproducible_results()` — same nonce + same input → same output IPs (in-order)
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `WebhostFarm`, `DiscoveredHost`, candidate iterator
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract `CandidateIterator(subnets, cap, seed)` into its own file with isolated tests

## Definition of done

All 8 unit tests green. `WebhostFarm` consumed by `add-cidr-whitelist-detector` and `add-ipv4-whitelisted-subnet-discovery`.

## Work log

- 2026-05-11: Added `WebhostFarm`, `DiscoveredHost`, injectable `WebhostProbe`, CIDR candidate expansion, max-candidate sampling, count-limited discovery, TCP/TLS probe filtering, and reverse ASN/org enrichment with focused unit coverage. The default probe currently uses Android/JVM socket TLS fallback while the native diagnostic TLS contract remains tracked by `add-utls-diagnostic-probe-clienthello-fingerprinting`.

Remaining before close:

- Replace or wrap the default `SocketWebhostProbe` with the finalized diagnostic owned-TLS client once `add-utls-diagnostic-probe-clienthello-fingerprinting` lands its native ClientHello fixture/contract slice.
- Add optional integration to enrich discovered hosts with certificate hostnames once `add-tls-cert-sni-discoverer` is unblocked.
