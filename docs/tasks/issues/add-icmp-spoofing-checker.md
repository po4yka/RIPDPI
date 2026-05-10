---
title: Add IcmpSpoofingChecker for RKN ICMP Forgery Detection
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add IcmpSpoofingChecker for RKN ICMP Forgery Detection #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `IcmpSpoofingChecker` that pings an RKN-blocked target and a control target, then flags suspicious results — a reply from the blocked target indicates RKN is forging ICMP unreachable responses, which means the device is bypassing blocking.

## Context

RKN sends forged ICMP Destination Unreachable packets to connections attempting to reach blocked resources. When bypass is active, these forged packets are intercepted. A ping to a reliably blocked target (e.g. `instagram.com`) that receives a reply indicates the device is not seeing the RKN block — i.e., bypass is working. An impossibly low RTT (<10ms) on the control target further suggests a locally spoofed response.

The check is suppressed when home-routed roaming is detected (foreign SIM on Russian network) to avoid false positives.

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/IcmpSpoofingChecker.kt`
**SystemPingProber reference:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/` — shells out to `ping` binary

**RIPDPI extension points:**
- New `IcmpSpoofingChecker.kt` in `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/`
- Add `IcmpSpoofingResult` to `DetectionModels.kt`
- Checker receives `homeRoutedRoaming: Boolean` from `LocationSignalsChecker` result to gate suppression

## Acceptance criteria

- [ ] Blocked target: `instagram.com` (RKN-blocked); control target: `google.com`
- [ ] IPv4 addresses resolved via DNS before ping
- [ ] `ping` shelled out with a 3-second timeout and 1 packet count; stdout/stderr parsed for RTT
- [ ] Reply from blocked target → `NEEDS_REVIEW` with `EvidenceConfidence.MEDIUM`
- [ ] Control RTT < 10ms → `NEEDS_REVIEW` with `EvidenceConfidence.MEDIUM` (local spoof suspected)
- [ ] Both conditions met → finding confidence upgrades to `EvidenceConfidence.HIGH`
- [ ] Result suppressed (marked `SKIPPED`) when `homeRoutedRoaming = true`
- [ ] Unit tests: mock ping output for success/failure/low-RTT scenarios

## TDD workflow

1. **Write tests first** — stub a `PingProber` interface to avoid shelling out in tests:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/IcmpSpoofingCheckerTest.kt`:
     - `reply_from_blocked_target_produces_needs_review_finding()` — fake prober returns success for `instagram.com`; assert `NEEDS_REVIEW` with `EvidenceConfidence.MEDIUM`; fails until checker exists
     - `control_rtt_below_10ms_produces_needs_review_finding()` — fake prober returns RTT 5ms for `google.com`; assert `NEEDS_REVIEW` finding
     - `both_conditions_upgrade_confidence_to_high()` — blocked target replies AND control RTT < 10ms; assert `EvidenceConfidence.HIGH`
     - `result_skipped_when_home_routed_roaming_true()` — pass `homeRoutedRoaming=true`; assert result state is `SKIPPED` and no findings
2. **Confirm red** — `./gradlew :core:detection:test` — all 4 fail
3. **Implement** — `PingProber` interface + `SystemPingProber` real impl + `IcmpSpoofingChecker` + port adapter
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract target constants; clean up RTT threshold

## Definition of done

Unit tests green. ICMP spoofing card visible in `DetectionCheckScreen`. Suppressed state shown as informational when roaming is detected.
