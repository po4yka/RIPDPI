---
title: Add RttTriangulationChecker for Physical Location Inference
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

- [ ] #task Add RttTriangulationChecker for Physical Location Inference #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `RttTriangulationChecker` that measures RTT to 5 Russian and 5 foreign hosts and infers physical location: a median RTT > 80ms to Russian hosts suggests the device is not physically in Russia, corroborating a bypass/exit in another country.

## Context

A VPN or proxy that routes all traffic through a foreign server increases the round-trip time to domestic Russian hosts. RTT triangulation exploits this: if `yandex.ru` takes >80ms on a device that claims to be in Russia, traffic is likely exiting abroad. The check only runs when the home country is resolved as Russia (from `LocationSignalsChecker`). High jitter (>60ms on majority of targets) downgrades confidence to avoid penalizing congested networks.

This check is disabled by default.

**RU targets:** `yandex.ru`, `mail.ru`, `vk.com`, `sberbank.ru`, `gosuslugi.ru`
**Foreign targets:** `facebook.com`, `github.com`, `twitter.com`, `reddit.com`, `instagram.com`

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/RttTriangulationChecker.kt`

**RIPDPI extension points:**
- New `RttTriangulationChecker.kt` in `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/`
- Add `PREF_RTT_TRIANGULATION_ENABLED` to detection settings; default `false`
- Checker receives resolved home country from `LocationSignalsChecker` result; skips if not Russia
- Add `RttTriangulationResult` to `DetectionModels.kt`

## Acceptance criteria

- [ ] Checker is disabled by default; no network calls made when disabled
- [ ] Only runs when home country = Russia (from location signals); result is `SKIPPED` otherwise
- [ ] Pings 5 RU + 5 foreign targets; individual failures excluded from median calculation
- [ ] Median RU RTT > 80ms → `NEEDS_REVIEW` with `EvidenceConfidence.MEDIUM`
- [ ] Jitter > 60ms on majority of targets → confidence downgraded to `EvidenceConfidence.LOW`
- [ ] Foreign median RTT cross-checked: if foreign RTT is also high (congested network), confidence further downgraded
- [ ] Result includes per-target RTT list for display in UI
- [ ] Unit tests with mock ping output covering: in-Russia RTT, exit-abroad RTT, high-jitter scenario

## TDD workflow

1. **Write tests first** — reuse `PingProber` interface from `IcmpSpoofingChecker`:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/RttTriangulationCheckerTest.kt`:
     - `no_calls_when_disabled()` — checker disabled; assert prober never called; fails until guard exists
     - `skipped_when_home_country_is_not_russia()` — pass `homeCountry="DE"`; assert result is `SKIPPED`
     - `needs_review_when_ru_median_above_80ms()` — fake RU targets return avg 100ms, foreign targets 200ms; assert `NEEDS_REVIEW` with `EvidenceConfidence.MEDIUM`
     - `confidence_downgraded_when_jitter_above_60ms()` — inject high-variance RTT values; assert confidence `EvidenceConfidence.LOW`
     - `no_finding_when_ru_median_below_80ms()` — fake RU targets return avg 30ms; assert no mismatch finding
2. **Confirm red** — `./gradlew :core:detection:test` — all 5 fail
3. **Implement** — `RttTriangulationChecker`, settings gate, port adapter
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract target lists and thresholds to constants

## Definition of done

Unit tests green. When enabled, RTT card visible in `DetectionCheckScreen` with per-target RTT breakdown.
