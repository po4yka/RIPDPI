---
title: Add IpConsensus Cross-Channel Synthesis Layer
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: detection-feature-parity-epic
blocks: [upgrade-verdict-engine-rules-matrix]
blocked_by: [add-ip-comparison-checker, upgrade-geo-ip-checker-multi-provider, add-cdn-pulling-checker, add-native-signs-checker]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add IpConsensus Cross-Channel Synthesis Layer #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add an `IpConsensusBuilder` that aggregates every observed public IP across all detection channels after all checkers complete, identifies channel conflicts and cross-channel mismatches, and produces a structured `IpConsensusResult` consumed by `VerdictEngine`.

## Context

Individual checkers each observe one or more public IPs. A bypass tool may show a Russian IP to RU-hosted checkers while showing a foreign IP to non-RU checkers. Without a synthesis layer, these divergences are only visible within individual checker results; the verdict engine can miss the combined signal. `IpConsensusBuilder` runs as a final aggregation step after all checker `Deferred` values are resolved — mirroring how `VpnCheckRunner` in RKNHardering does it.

**Channels aggregated:** GeoIP (5 providers), IpComparison (RU group + non-RU group), CdnPulling (per-endpoint per-protocol), TUN probe (VPN path + underlying path from `BypassChecker`), local proxy scan IP, STUN reflexive address (when `CallTransportLeakChecker` is enabled).

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/ipconsensus/IpConsensusBuilder.kt`
**Orchestration reference:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VpnCheckRunner.kt` — see post-`awaitAll` section

**RIPDPI placement:** new `consensus/` subpackage inside `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/`

**RIPDPI integration point:** `DetectionPipelineResultAssembler.kt` — call `IpConsensusBuilder.build()` after all checker results are available, before passing to `VerdictEngine`.

## Acceptance criteria

- [ ] `IpConsensusResult` contains: `observedIps: Map<Channel, List<String>>`, `channelConflicts: List<ChannelConflict>`, `crossChannelMismatches: List<CrossChannelMismatch>`, `foreignIps: List<String>`, `warpIndicator: Boolean`
- [ ] ASN resolved for each distinct observed IP (reuse existing `GeoIpChecker` client)
- [ ] Channel conflict: same channel reports 2+ different IPs → `EvidenceConfidence.HIGH`
- [ ] Cross-channel mismatch: RU-group IP ≠ non-RU-group IP → `EvidenceConfidence.HIGH`
- [ ] Warp-like indicator: Cloudflare ASN on underlying path while VPN path shows different ASN
- [ ] `IpConsensusResult` added to `DetectionCheckResult` and passed to `VerdictEngine`
- [ ] Unit tests: construct fake checker results with known IP sets; assert conflict/mismatch detection

## TDD workflow

1. **Write tests first** — create before any implementation:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/consensus/IpConsensusBuilderTest.kt`:
     - `channel_conflict_detected_when_same_channel_reports_two_ips()` — inject `GeoIpChannel` with IPs `[1.1.1.1, 2.2.2.2]`; assert `channelConflicts` non-empty with `EvidenceConfidence.HIGH`; fails until builder exists
     - `cross_channel_mismatch_detected_when_ru_and_non_ru_differ()` — inject RU-group IP `1.2.3.4` and non-RU-group IP `5.6.7.8`; assert `crossChannelMismatches` non-empty
     - `no_conflict_when_all_channels_agree()` — all channels report same IP; assert both lists empty
     - `warp_indicator_true_when_cloudflare_asn_on_underlying_path()` — inject Cloudflare ASN 13335 on underlying path, different ASN on VPN path; assert `warpIndicator=true`
     - `foreign_ips_list_excludes_russian_prefixes()` — inject mix of RU and non-RU IPs; assert `foreignIps` contains only non-RU entries
2. **Confirm red** — `./gradlew :core:detection:test` — all 5 fail
3. **Implement** — `IpConsensusBuilder`, `IpConsensusResult`, wire into `DetectionPipelineResultAssembler`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract ASN lookup into a separate injectable dependency

## Definition of done

Unit tests green. `VerdictEngine` consumes `IpConsensusResult` (see `upgrade-verdict-engine-rules-matrix`). IpConsensus summary card visible in `DetectionCheckScreen`.
