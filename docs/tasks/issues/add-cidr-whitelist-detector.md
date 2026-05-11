---
title: Add CIDR-Whitelist Censorship Detector via Control vs Regular URL Group Probe
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-checkers-parity-epic
blocks: []
blocked_by: [add-webhost-farm-dynamic-host-discovery, add-utls-diagnostic-probe-clienthello-fingerprinting]
created: 2026-05-10
updated: 2026-05-11
---

- [ ] #task Add CIDR-Whitelist Censorship Detector via Control vs Regular URL Group Probe #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `CidrWhitelistDetector` that probes two URL groups in parallel — `whitelisted` (URLs known to be on a CIDR whitelist, e.g. Russian-domestic IP ranges) and `regular` (URLs on regular foreign IPs) — and returns one of three verdicts: `OK` (regular URLs reachable), `CIDR_WHITELIST_DETECTED` (only whitelisted URLs reachable), or `NO_INTERNET` (neither group reachable).

## Context

CIDR-whitelist censorship is a coarse blocking mode: the censor permits TCP/UDP connections only to a curated allowlist of IP subnets (e.g. Russian government and major Russian-domestic services). All other traffic is dropped at the routing layer. Distinct from DPI-on-SNI (which inspects content) and DNS poisoning (which manipulates resolution): CIDR whitelisting acts at L3/L4, before any DPI or DNS lookup matters.

Detection is straightforward when you have two reference groups:
- **Whitelisted group** (~5 URLs on known-allowed Russian IPs: gosuslugi.ru, mos.ru, sberbank.ru, vk.com, yandex.ru)
- **Regular group** (~5 URLs on foreign IPs that should *not* be on any whitelist: github.com, cloudflare.com, example.com, mozilla.org, wikipedia.org)

If the regular group succeeds at all → no CIDR whitelist (the user has open routing).
If only the whitelisted group succeeds → CIDR whitelist active.
If neither group succeeds → user has no internet.

dpi-ch's `cidrwhitelist.go` runs both groups in parallel with `context.WithTimeout`; the first regular-URL success cancels both contexts (no need to keep probing); the verdict is computed from the surviving counts.

**Reference:** `/Users/po4yka/GitRep/dpi-checkers/ru/dpi-ch/checkers/cidrwhitelist.go` (full file)

**RIPDPI placement:**
- Detector: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/CidrWhitelistDetector.kt`
- Result: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/CidrWhitelistResult.kt`
- Bundled URL groups: `core/diagnostics/src/main/assets/dpich/cidr_whitelisted_urls.txt`, `cidr_regular_urls.txt`

## Acceptance criteria

- [ ] `CidrWhitelistResult` enum: `OK`, `CIDR_WHITELIST_DETECTED`, `NO_INTERNET`
- [ ] `CidrWhitelistDetector.detect(timeoutMs: Long = 8000): CidrWhitelistDetectionResult` returning the verdict + per-URL probe traces (URL, group, status, latency, error if any)
- [ ] Both URL groups loaded from bundled assets via extended `DpiAssetLoader`; user-override supported per the existing override mechanism
- [ ] Default whitelisted group (5 URLs): gosuslugi.ru, mos.ru, sberbank.ru, vk.com, yandex.ru
- [ ] Default regular group (5 URLs): github.com, cloudflare.com, example.com, mozilla.org, wikipedia.org
- [ ] Per-URL probe: HTTP HEAD via `OkHttpClient` (using `add-utls-diagnostic-probe-clienthello-fingerprinting` for TLS); ignores body; timeout configurable
- [ ] Concurrency: all URLs in both groups probed in parallel via `coroutineScope { async { ... } }`
- [ ] Early-cancel: as soon as **any** regular-group URL succeeds, cancel all in-flight probes (verdict is `OK` regardless of whitelisted-group results) — matches dpi-ch's `regCancel(); wlCancel()` pattern
- [ ] Verdict logic: `regularOk > 0 → OK`; `regularOk == 0 && whitelistedOk > 0 → CIDR_WHITELIST_DETECTED`; both zero → `NO_INTERNET`
- [ ] Surfaced in DiagnosticsScreen as "CIDR Whitelist Detection" card with verdict + per-URL trace expandable
- [ ] Unit tests: all 3 verdict branches; cancel-on-first-regular-success behavior

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpich/CidrWhitelistDetectorTest.kt`:
     - `regular_url_succeeds_returns_ok()` — `MockWebServer` returns 200 for github.com; assert verdict `OK`; fails until detector exists
     - `only_whitelisted_succeeds_returns_cidr_whitelist_detected()` — mock returns 200 for vk.com, throws for github.com; assert `CIDR_WHITELIST_DETECTED`
     - `neither_group_succeeds_returns_no_internet()` — all probes throw; assert `NO_INTERNET`
     - `first_regular_success_cancels_remaining_probes()` — instrument; first regular probe completes in 100ms; assert later regular probes cancelled before timeout
     - `partial_regular_success_returns_ok_not_partial()` — 1/5 regular OK, 4/5 fail; assert `OK` (any success suffices)
     - `per_url_trace_populated()` — assert result contains 10 trace entries (5 whitelisted + 5 regular) with URL, status/error, latency
     - `user_override_replaces_url_groups()` — fake `filesDir/dpich/cidr_regular_urls.txt`; assert detector reads override
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 7 fail
3. **Implement** — `CidrWhitelistDetector`, result models, asset loader extension
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract per-URL probe into `probeOne(url): UrlProbeTrace` for reuse

## Definition of done

All 7 unit tests green. Card visible in DiagnosticsScreen Tools section. Per-URL trace expandable in UI. Verdict semantics match dpi-ch's three-state logic exactly.
