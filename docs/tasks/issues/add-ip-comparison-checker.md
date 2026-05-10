---
title: Add IpComparisonChecker with RU vs Non-RU Endpoint Groups
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: detection-feature-parity-epic
blocks: [add-ip-consensus-synthesis]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add IpComparisonChecker with RU vs Non-RU Endpoint Groups #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `IpComparisonChecker` to `core/detection` that queries 9 IP-reflection endpoints split into RU-hosted and non-RU-hosted groups, then detects IP mismatches across groups as confirmed bypass evidence.

## Context

RIPDPI's `GeoIpChecker` queries a single external provider (`ipwho.is`). RKNHardering uses a richer approach: query 9 endpoints across two groups and detect cross-group divergence. If a Russian-hosted checker sees IP-A and a foreign checker sees IP-B, the device is using a bypass that only affects traffic to non-RU endpoints (split-tunnel or selective routing).

**RU-hosted group:** `2ip.ru`, Yandex IPv4 (`ipv4-internet.yandex.net`), Yandex IPv6, `sypexgeo.net`, `mail.ru`
**Non-RU group:** `ifconfig.me` (IPv4 + IPv6 separately), `checkip.amazonaws.com`, `ipify.org`, `ip.sb` (IPv4 + IPv6)

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/IpComparisonChecker.kt`

**RIPDPI extension points:**
- New port interface in `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/` (alongside `GeoIpChecker.kt`)
- Wire in `DetectionCheckerPortAdapters.kt` + `DetectionCheckerPortsModule.kt`
- Inject into `DetectionPipelineScheduler.kt`
- Add `IpComparisonResult` to `DetectionModels.kt`
- Feed into `IpConsensus` synthesis (see `add-ip-consensus-synthesis`)

## Acceptance criteria

- [ ] All 9 endpoints queried in parallel; individual failures are swallowed and marked `ERROR`
- [ ] Each endpoint resolves DNS A/AAAA records alongside the HTTP IP-reflection response
- [ ] RU-group and non-RU-group IPs are compared; mismatch produces `EvidenceConfidence.HIGH` finding
- [ ] IPv6 endpoints run independently of IPv4; IPv6 absence is not an error
- [ ] Result is included in `DetectionCheckResult` and rendered in `DetectionCheckScreen`
- [ ] Unit tests: mock 9 endpoints, assert mismatch detection; assert graceful handling when ≥1 endpoint is unreachable

## TDD workflow

1. **Write tests first** — create before any implementation:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/IpComparisonCheckerTest.kt`:
     - `cross_group_mismatch_produces_high_confidence_finding()` — mock RU group returning `1.2.3.4`, non-RU group returning `5.6.7.8`; assert finding with `EvidenceConfidence.HIGH`; fails until checker exists
     - `no_finding_when_all_groups_agree()` — all 9 endpoints return same IP; assert no mismatch finding
     - `result_non_null_when_3_endpoints_unreachable()` — 3 of 9 return `IOException`; assert result non-null with remaining endpoints recorded
     - `ipv6_absence_not_an_error()` — IPv6 endpoints time out; assert no error finding, result still valid
2. **Confirm red** — `./gradlew :core:detection:test` — all 4 fail
3. **Implement** — `IpComparisonChecker`, port interface, adapter wiring
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract endpoint group constants

## Definition of done

Unit tests green. `IpComparisonResult` visible in the detection check UI card. Cross-group mismatch promotes the verdict to at least `NEEDS_REVIEW`.
