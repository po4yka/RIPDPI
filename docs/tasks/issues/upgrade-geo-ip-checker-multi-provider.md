---
title: Upgrade GeoIpChecker to 5-Provider Majority-Vote Consensus
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

- [ ] #task Upgrade GeoIpChecker to 5-Provider Majority-Vote Consensus #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Replace the single-provider `GeoIpChecker` (currently `ipwho.is`) with a 5-provider parallel query that merges results via majority voting on proxy/hosting flags.

## Context

A single GeoIP provider can be blocked or return stale data. RKNHardering queries 5 providers simultaneously and resolves conflicts with majority voting. This significantly reduces false negatives on proxy/hosting classification.

**Providers to add:** `ipapi.is`, `iplocate.io`, `ipquery.io`, `iplookup.it`, `ipbot.com` (keep `ipwho.is` or replace — align with RKNHardering's final list)

**Logic:** First fetch seeds the known IP, then all 5 providers are re-queried with that IP. Merged `GeoIpFacts`: country code (majority), ASN (majority), proxy flag (majority of ≥3), hosting flag (majority of ≥3).

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/GeoIpChecker.kt`

**RIPDPI file to modify:** `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/GeoIpChecker.kt`

## Acceptance criteria

- [ ] 5 providers queried in parallel; individual failures do not abort the check
- [ ] Seed IP fetched once; subsequent queries use the known IP
- [ ] Proxy flag resolved by majority vote (≥3 of 5 providers agree)
- [ ] Hosting flag resolved by majority vote
- [ ] Country code resolved by majority; tie goes to the most recent successful response
- [ ] `GeoIpFacts` model extended with `providerCount: Int` and `conflictingProviders: List<String>`
- [ ] Unit tests: assert majority logic with 3-2 splits; assert 2-provider failure still produces a result

## TDD workflow

1. **Write tests first** — create before any implementation:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/GeoIpCheckerTest.kt`:
     - `majority_proxy_flag_true_when_3_of_5_agree()` — inject 5 mock responses (3 proxy=true, 2 proxy=false); assert merged `proxyDb=true`; fails until majority logic exists
     - `majority_proxy_flag_false_when_2_of_5_agree()` — inject (2 true, 3 false); assert `proxyDb=false`
     - `result_returned_when_2_providers_fail()` — 2 providers return HTTP 500; assert result non-null with `providerCount=3`
     - `provider_count_equals_successful_responses()` — assert `geoFacts.providerCount == 5` when all succeed
2. **Confirm red** — `./gradlew :core:detection:test` — all 4 tests fail (class/method not found or wrong logic)
3. **Implement** — extend `GeoIpChecker` with 5-provider parallel query + majority merge
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract provider list to a constant; remove old single-provider code path

## Definition of done

Unit tests green. `DetectionCheckScreen` GeoIP card shows provider count alongside result.
