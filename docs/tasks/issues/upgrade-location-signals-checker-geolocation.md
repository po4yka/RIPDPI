---
title: Upgrade LocationSignalsChecker with BeaconDB Cell Tower and Wi-Fi Geolocation
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

- [ ] #task Upgrade LocationSignalsChecker with BeaconDB Cell Tower and Wi-Fi Geolocation #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Extend the existing `LocationSignalsChecker` to geolocate the device via BeaconDB using cell tower data and nearby Wi-Fi BSSIDs, then reverse-geocode the result to a country — providing a physical location signal independent of SIM/MCC.

## Context

RIPDPI's `LocationSignalsChecker` currently reads MCC/MNC, SIM country, roaming status, and BSSID. It does not resolve these to an actual geographic location. RKNHardering extends this by querying **BeaconDB** (open cell tower and Wi-Fi geolocation API) with up to 6 cell towers and 12 nearby BSSIDs, then reverse-geocodes the latitude/longitude result via Android `Geocoder`. This provides a physical location estimate that can contradict a VPN-assigned country.

**Reference implementation:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/LocationSignalsChecker.kt`

**RIPDPI file to modify:** `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/LocationSignalsChecker.kt`

**New sub-checks to add:**
- Cell tower collection via `requestCellInfoUpdate` (fresh) + cached fallback; up to 6 towers (GSM, LTE, WCDMA, NR/5G cell identities)
- Wi-Fi BSSID scan: fresh scan + cached; up to 12 APs
- BeaconDB HTTP query with collected towers + BSSIDs
- Android `Geocoder.getFromLocation()` reverse-geocode on BeaconDB lat/lon
- Home-routed-roaming detection: foreign SIM MCC on Russian visited network → set `homeRoutedRoaming = true` in result

**New machine-readable findings keys:** `cell_country_ru:true`, `location_country_ru:true`, `home_routed_roaming:true`

## Acceptance criteria

- [ ] `requestCellInfoUpdate` used for fresh cell info with 2-second timeout; falls back to `allCellInfo` cache
- [ ] Up to 6 cell towers collected; multi-SIM handled via `SubscriptionManager`
- [ ] Up to 12 Wi-Fi BSSIDs collected (fresh scan triggered; cached scan used if scan unavailable)
- [ ] BeaconDB API queried with collected cell + BSSID data; HTTP failure is non-fatal
- [ ] Reverse-geocoded country compared to MCC country; mismatch is a separate finding
- [ ] `homeRoutedRoaming` flag set when foreign SIM (non-RU MCC) is on a Russian visited network; flag propagated to pipeline for ICMP and CDN result relaxation
- [ ] `NEARBY_WIFI_DEVICES` / `ACCESS_FINE_LOCATION` permission handling delegated to `DetectionPermissionPlanner`
- [ ] Unit tests: mock BeaconDB response, assert country extraction; assert home-routed-roaming detection

## TDD workflow

1. **Write tests first** — stub `BeaconDbClient` and `CellInfoProvider` interfaces:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/LocationSignalsCheckerTest.kt`:
     - `beacondb_country_extracted_from_valid_response()` — fake client returns lat/lon for Russia; fake `Geocoder` returns `"RU"`; assert `locationCountryRu=true`; fails until BeaconDB integration exists
     - `home_routed_roaming_set_when_foreign_sim_on_ru_network()` — inject SIM MCC `276` (foreign), visited MCC `250` (Russia); assert `homeRoutedRoaming=true`
     - `beacondb_failure_does_not_fail_checker()` — fake client throws `IOException`; assert result non-null with `locationCountryRu=null`
     - `cell_country_ru_true_when_geocoder_returns_ru()` — inject 3 cell towers; fake Geocoder resolves to Russia; assert `cellCountryRu=true`
2. **Confirm red** — `./gradlew :core:detection:test` — all 4 fail
3. **Implement** — `BeaconDbClient`, cell tower + BSSID collection, `Geocoder` reverse-geocode, extend `LocationSignalsChecker`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract BeaconDB API URL constant; separate cell vs Wi-Fi collection into private functions

## Definition of done

Unit tests green. Location card in `DetectionCheckScreen` shows resolved country from BeaconDB alongside SIM-derived country. Home-routed-roaming state suppresses ICMP and CDN findings correctly.
