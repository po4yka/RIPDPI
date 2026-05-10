---
title: Add Random Host Header Mode for Diagnostic Probes
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: [add-tcp16-fat-header-dpi-probe]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Random Host Header Mode for Diagnostic Probes #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `RandomHostHeaderGenerator` plus a probe-config flag `randomHostname: Boolean` that, when enabled, generates a fresh random hostname (e.g. `<rand-15-chars>.com`) for each diagnostic request's HTTP `Host` header — defeating censors that whitelist the diagnostic tool's fixed hostname patterns.

## Context

dpi-ch's `webhost-item` config supports `random-hostname: bool` per host group. The reasoning: a censor that observes diagnostic-tool traffic can identify it by the fixed hostnames it sends (e.g. always probing `example.com`, `google.com`) and decide to whitelist just those — producing false-negative test results. Generating a fresh random hostname per request defeats this attack: the censor can't distinguish probe traffic from accidentally-malformed user traffic, and can't whitelist by hostname pattern.

Critical: the random hostname goes in the **HTTP `Host` header** (and TLS SNI when port is 443), but the **TCP target IP is unchanged** — the test still hits the intended host. The mismatch between Host header and IP routing is intentional and matches how the censor model works (it inspects Host/SNI, not the underlying IP).

**Generator details:**
- Hostname: 15 random ASCII lowercase letters + digits, prefix-leading-letter (since hostnames must start with a letter), TLD picked uniformly from `[".com", ".net", ".org", ".io", ".info"]`
- Per-request fresh: do not cache; each request gets a new random name
- Match the same prober's `tcp1620_prober.py` `FAKE_DOMAIN_LEN = 15` constant for consistency

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/docs/README.md` (`webhost-item.random-hostname`) + `/Users/po4yka/GitRep/dpi-checkers/utils/tcp1620_prober.py` `FAKE_DOMAIN_LEN`

**RIPDPI placement:**
- Generator: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/RandomHostHeaderGenerator.kt`
- Probe config flag added to `Tcp16FatHeaderProbe`, `WebhostFarm`, `DomainReachabilityScanner`

## Acceptance criteria

- [ ] `RandomHostHeaderGenerator.next(): String` returns a valid hostname matching `[a-z][a-z0-9]{14}\.(com|net|org|io|info)`
- [ ] No state shared across calls — each call returns a fresh random hostname; thread-safe (`ThreadLocalRandom` or a `SecureRandom` per-instance)
- [ ] Probe-config flag added: `Tcp16FatHeaderProbe.run(target, ..., randomHostname: Boolean = false)`; `WebhostFarm.discover(..., randomHostname: Boolean = false)`; `DomainReachabilityScanner.scan(..., randomHostname: Boolean = false)`
- [ ] When `randomHostname = true`: each request's `Host` header is a fresh random hostname; SNI also random (matches `Host`); IP routing unchanged
- [ ] When `randomHostname = false` (default): existing behaviour preserved (real Host/SNI matching the target)
- [ ] Result struct records the random hostname used per request for debug — accessible via `probeResult.requestedHosts: List<String>?`
- [ ] Setting in detection settings: "Use random hostnames in diagnostic probes (defeats censor whitelisting of fixed test domains)" — applies suite-wide default
- [ ] Unit tests: hostname format regex; uniqueness across N calls; per-request hostname recorded in result

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/RandomHostHeaderGeneratorTest.kt`:
     - `generated_hostname_matches_format_regex()` — generate 100 hostnames; assert each matches `^[a-z][a-z0-9]{14}\.(com|net|org|io|info)$`; fails until generator exists
     - `consecutive_calls_return_different_values()` — 100 calls; assert all distinct (with overwhelming probability for 15 random chars)
     - `tld_distribution_uniform()` — 1000 calls; assert each TLD appears 200±50 times (chi-squared sanity)
     - `thread_safe_under_concurrent_calls()` — 16 threads × 100 calls each; assert no duplicates, no exceptions
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/Tcp16FatHeaderProbeRandomHostTest.kt`:
     - `random_host_header_changes_per_request()` — capture all 16 requests' `Host` headers; assert all 16 distinct
     - `tcp_routing_unchanged_when_random_host_enabled()` — instrument TCP connect; assert target IP matches config, not the random hostname's would-be DNS
     - `random_hosts_recorded_in_result()` — assert `result.requestedHosts.size == 16`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 7 fail
3. **Implement** — `RandomHostHeaderGenerator`, probe-config flag plumbing, settings entry
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — define a single `RandomHostHeaderConfig` shared by all probe configs

## Definition of done

All 7 unit tests green. Setting toggle in detection settings. Per-probe `randomHostname` flag overridable from suite runner.
