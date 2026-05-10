---
title: Upgrade VerdictEngine to 6-Rule Matrix with IpConsensus and Roaming Relaxation
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: [add-ip-consensus-synthesis]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Upgrade VerdictEngine to 6-Rule Matrix with IpConsensus and Roaming Relaxation #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Replace `VerdictEngine`'s current weighted scoring with an explicit 6-rule evaluation matrix that consumes `IpConsensusResult` and applies roaming relaxation to CDN and ICMP findings before computing the final verdict.

## Context

RIPDPI's `VerdictEngine` uses a weighted scoring approach (HIGH=5, MEDIUM=3, LOW=1 with source bonuses). RKNHardering's engine uses a 6-rule declarative matrix that produces more predictable, debuggable verdicts and incorporates the cross-channel `IpConsensus` result as a first-class input. The roaming relaxation step retroactively downgrades CDN pulling and ICMP spoofing findings when home-routed roaming is detected — preventing false positives for foreign SIM holders on Russian networks.

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VerdictEngine.kt`
**Orchestration / relaxation reference:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VpnCheckRunner.kt` — `annotateExpectedRoamingExit()`, `relaxCdnPulling()`, `relaxIcmpSpoofing()` calls

**RIPDPI file to modify:** `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/VerdictEngine.kt`

**6 rules from RKNHardering:**
- R1: Hard-detect bypass evidence (SPLIT_TUNNEL_BYPASS, XRAY_API, VPN_GATEWAY_LEAK, VPN_NETWORK_BINDING) → `DETECTED`
- R2: (reserved / implementation-specific)
- R3: IpConsensus divergence + geo axis → `DETECTED` or `NEEDS_REVIEW`
- R4: Location confirms Russia + foreign GeoIP → `DETECTED`
- R5: 3-bit matrix (geoHit × directHit × indirectHit) → `DETECTED` / `NEEDS_REVIEW`
- R6: NEEDS_REVIEW fallbacks (call transport, ICMP, RTT, native hooks) → `NEEDS_REVIEW`

## Acceptance criteria

- [ ] `VerdictEngine.evaluate()` accepts `DetectionCheckResult` including `IpConsensusResult`
- [ ] Roaming relaxation applied before rule evaluation: CDN and ICMP findings downgraded when `homeRoutedRoaming = true`
- [ ] R1 hard-detect rule fires first and short-circuits remaining rules → `DETECTED`
- [ ] R3 IpConsensus cross-channel mismatch → `DETECTED`; single-channel conflict → `NEEDS_REVIEW`
- [ ] R4 location + GeoIP axis works with new 5-provider GeoIP consensus result
- [ ] R5 3-bit matrix covers cases where new checkers (IpComparison, CdnPulling, NativeSigns) provide the direct/indirect/geo hits
- [ ] R6 NEEDS_REVIEW fallback covers: IcmpSpoofing, RttTriangulation, NativeSigns hook detection, CallTransportLeak
- [ ] All existing `VerdictEngine` unit tests remain green; new tests added for each rule
- [ ] `VerdictExplanation` model extended with `ruleApplied: String` for debuggability

## TDD workflow

1. **Write tests first** — one test class per rule, all failing until the new engine is implemented:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/VerdictEngineTest.kt`:
     - `r1_xray_api_evidence_produces_detected()` — inject result with `XRAY_API` evidence source; assert `Verdict.DETECTED` and `ruleApplied="R1"`; fails until R1 exists
     - `r1_split_tunnel_bypass_produces_detected()` — inject `SPLIT_TUNNEL_BYPASS` evidence; assert `DETECTED`
     - `r3_ip_consensus_cross_channel_mismatch_produces_detected()` — inject `IpConsensusResult` with `crossChannelMismatches` non-empty; assert `DETECTED` and `ruleApplied="R3"`
     - `r4_location_ru_and_foreign_geo_ip_produces_detected()` — inject RU location signal + foreign GeoIP; assert `DETECTED` and `ruleApplied="R4"`
     - `r5_all_three_bits_set_produces_detected()` — inject geoHit + directHit + indirectHit; assert `DETECTED`
     - `r6_icmp_alone_produces_needs_review()` — inject only IcmpSpoofing finding, no other signals; assert `NEEDS_REVIEW` and `ruleApplied="R6"`
     - `roaming_relaxation_downgrades_cdn_before_rule_evaluation()` — inject CDN finding + `homeRoutedRoaming=true`; assert CDN finding confidence downgraded before rules fire; assert final verdict `NOT_DETECTED`
     - `existing_tests_pass()` — all pre-existing `VerdictEngineTest` cases remain green
2. **Confirm red** — `./gradlew :core:detection:test` — new tests fail (wrong verdict or wrong rule name)
3. **Implement** — replace weighted scoring with 6-rule matrix; add roaming relaxation pre-pass; add `ruleApplied` to `VerdictExplanation`
4. **Confirm green** — `./gradlew :core:detection:test` — all old + new tests pass
5. **Refactor** — remove dead weighted-scoring code; extract rule functions

## Definition of done

All unit tests green. `VerdictExplanation` visible in `DetectionCheckScreen` with the rule that fired. Weighted scoring code removed. Roaming relaxation tested with a known roaming scenario.
