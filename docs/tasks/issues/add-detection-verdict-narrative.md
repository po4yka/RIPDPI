---
title: Add Rich VerdictNarrative with Exposure Status and Discovery Rows
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: detection-feature-parity-epic
blocks: [add-detection-export-share]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Rich VerdictNarrative with Exposure Status and Discovery Rows #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Replace the current flat verdict display in `DetectionCheckScreen` with a structured `VerdictNarrative` that includes an exposure status hierarchy, a "what was discovered" list, a "why this verdict" reason list, and a home-routed-roaming note.

## Context

RIPDPI currently shows a single verdict label + subtitle. RKNHardering's `VerdictNarrative` provides richer context: *what* was exposed (exposure status), *what* was found (discovered rows), and *why* the verdict was reached (reason rows). This makes the result actionable for users troubleshooting their bypass setup.

**Exposure status hierarchy (highest to lowest):**
`REMOTE_ENDPOINT_DISCOVERED` > `PUBLIC_IP_ONLY` > `LOCAL_PROXY_OR_API_ONLY` > `TECHNICAL_SIGNAL_ONLY` > `INSUFFICIENT_DATA`

**Discovered rows (up to shown):** exposure level, Xray API endpoint, up to 3 remote endpoints, local proxy, owner app, VPN network IP, real IP, direct IP, proxy IP, call transport leaks (up to 4), RU/nonRU checker IPs, geo IP

**Reason rows (up to 5):** Xray API, split tunnel bypass, VPN gateway leak, VPN network binding, bypass unconfirmed, IP comparison detected/review, geo/location conflict, geo foreign, direct signs, indirect signs, call transport signal, ICMP signal; fallback reason if none match

**Home-routed roaming note:** shown when `homeRoutedRoaming=true` — explains that foreign SIM users on Russian network may see false positives

**Reference:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VerdictNarrative.kt`

**RIPDPI placement:**
- New `VerdictNarrative.kt` in `core/detection` — pure data class produced by `VerdictEngine`
- `DetectionCheckUiState` gains `narrative: VerdictNarrative?`
- `DetectionResultCards.kt` — new `VerdictNarrativeCard` composable: exposure chip, discovered list, reason list, roaming note

## Acceptance criteria

- [ ] `ExposureStatus` enum with 5 levels; `VerdictEngine` computes it from `DetectionCheckResult`
- [ ] `discoveredRows` list constructed in priority order; max 10 rows; rows with null values omitted
- [ ] `reasonRows` list constructed from matching evidence; max 5 rows; fallback row always present
- [ ] `homeRoutedRoamingNote: String?` non-null when `LocationSignalsResult.homeRoutedRoaming=true`
- [ ] `VerdictNarrativeCard` composable renders: exposure chip (colored by severity), scrollable discovered list, reason list with icons, roaming note as warning callout
- [ ] Verdict hero card in `DetectionCheckScreen` shows exposure status chip alongside DETECTED/NEEDS_REVIEW/NOT_DETECTED label
- [ ] Narrative included in both Markdown and JSON export (see `add-detection-export-share`)
- [ ] Unit tests: assert exposure level computed correctly for 5 input scenarios; assert reason rows match expected for known `DetectionCheckResult` fixtures

## TDD workflow

1. **Write tests first** — pure data transformation, no Android deps needed:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/VerdictNarrativeTest.kt`:
     - `remote_endpoint_discovered_when_xray_api_present()` — inject result with `XRAY_API` evidence; assert `exposureStatus=REMOTE_ENDPOINT_DISCOVERED`; fails until `VerdictNarrative` exists
     - `public_ip_only_when_geo_but_no_endpoint()` — inject GeoIP foreign finding, no local proxy; assert `PUBLIC_IP_ONLY`
     - `insufficient_data_when_no_positive_signals()` — inject all-clean result; assert `INSUFFICIENT_DATA`
     - `reason_rows_contain_xray_api_reason_when_xray_found()` — inject Xray API finding; assert `reasonRows` contains Xray API entry
     - `reason_rows_capped_at_five()` — inject 10 different evidence sources; assert `reasonRows.size <= 5`
     - `home_routed_roaming_note_non_null_when_flag_set()` — inject `homeRoutedRoaming=true`; assert `homeRoutedRoamingNote != null`
     - `discovered_rows_capped_at_ten()` — inject fixture with many endpoints; assert `discoveredRows.size <= 10`
2. **Confirm red** — `./gradlew :core:detection:test` — all 7 fail
3. **Implement** — `VerdictNarrative`, `ExposureStatus`, `VerdictNarrativeCard` composable, wire into `VerdictEngine`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract exposure level computation into a pure function; reuse in export formatter

## Definition of done

Unit tests green. Verdict card shows exposure status, discovered list, and reason list. Export includes narrative fields.
