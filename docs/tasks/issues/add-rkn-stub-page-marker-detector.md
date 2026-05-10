---
title: Add Russian ISP Stub-Page Marker Detector
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: rkn-block-checker-parity-epic
blocks: [add-rkn-layered-probe-pipeline]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Russian ISP Stub-Page Marker Detector #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `RknStubPageDetector` that matches an HTTP response body against a curated library of ~10 Russian-language ISP block-page substring markers, plus an HTTP-451 short-circuit, and returns a confidence-tagged verdict (`HIGH` for marker match or 451; `LOW` for no match).

## Context

Russian ISPs serve "blocked by Roskomnadzor" stub pages with HTTP 200 (not 451), so reachability alone won't catch them. The fingerprint is in the body: a small set of Russian-language phrases that appear on virtually every ISP stub page (`доступ ограничен`, `решению роскомнадзора`, `единый реестр`, `blocked by rkn`, etc.). `rkn-block-checker` matches against these on the first 2KB of the response body, lowercased.

The marker list is **deliberately narrow** — broader phrases like `роскомнадзор` alone produce false positives on news articles that legitimately mention the regulator. Each marker was chosen to be specific to ISP stub-page templates while being generic across ISPs.

**The 10 markers (verbatim from `targets.STUB_MARKERS`):**
1. `доступ ограничен`
2. `доступ к запрашиваемому ресурсу`
3. `решению роскомнадзора`
4. `решением суда`
5. `заблокирован`
6. `blocked by roskomnadzor`
7. `blocked by rkn`
8. `rkn.gov.ru/org/register`
9. `единый реестр`
10. `запрещен`

**Match window:** `body[:2000].lowercase()` — substring match. 2KB is enough to catch the stub message in the page header without paying the cost of buffering full responses.

**Verdict & confidence:**
- HTTP status 451 → `HTTP_STUB` with `HIGH` confidence (explicit "Unavailable For Legal Reasons")
- Body matches any marker → `HTTP_STUB` with `HIGH` confidence (note: which marker matched)
- Otherwise → no stub detected (caller decides verdict)

**Reference:** `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/http.py` (`looks_like_stub`, `STUB_MARKERS` import) + `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/targets.py` (lines 49-62)

**RIPDPI placement:**
- Detector: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknStubPageDetector.kt`
- Marker config: `core/diagnostics/src/main/assets/rkn/stub_markers.txt` (one marker per line; allows runtime extension via user override without code change)
- Loader: extends `DpiAssetLoader` with `loadStubMarkers(): List<String>`

## Acceptance criteria

- [ ] `stub_markers.txt` bundled with all 10 markers from `targets.STUB_MARKERS`
- [ ] `DpiAssetLoader.loadStubMarkers(): List<String>` reads asset; skips `#`-comments; cached
- [ ] User override: `filesDir/rkn/stub_markers.txt` takes precedence (allows community-sourced new markers)
- [ ] `RknStubPageDetector.detect(body: String, statusCode: Int): StubDetection`
- [ ] `StubDetection`: `isStub: Boolean`, `confidence: Confidence (HIGH | MEDIUM | LOW)`, `matchedMarker: String?`, `via451: Boolean`
- [ ] HTTP 451 → `StubDetection(isStub = true, confidence = HIGH, matchedMarker = null, via451 = true)`
- [ ] Body matches a marker (case-insensitive, in first 2000 chars) → `StubDetection(isStub = true, confidence = HIGH, matchedMarker = "<marker>", via451 = false)`
- [ ] No match → `StubDetection(isStub = false, confidence = LOW, ...)`
- [ ] Body normalization: `body.take(2000).lowercase(Locale.ROOT)` before matching (locale-root to handle Cyrillic uppercase consistently)
- [ ] Unit tests: each of the 10 markers matched independently; HTTP 451 short-circuit; non-matching body returns `isStub = false`; case-insensitive match (`ДОСТУП ОГРАНИЧЕН` → match)

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknStubPageDetectorTest.kt`:
     - `http_451_returns_high_confidence_via_451()` — call with `body = "", statusCode = 451`; assert `isStub && confidence == HIGH && via451`; fails until detector exists
     - `marker_dostup_ogranichen_matches()` — body contains `"доступ ограничен"`; assert `matchedMarker == "доступ ограничен"`
     - `marker_blocked_by_rkn_matches()` — body contains `"blocked by rkn"`; assert match
     - `case_insensitive_match()` — body contains `"ДОСТУП ОГРАНИЧЕН"`; assert match
     - `match_window_limited_to_first_2000_chars()` — marker at position 5000 → no match
     - `non_matching_body_returns_false()` — body of plain HTML; assert `!isStub`
     - `parameterized_all_10_markers_match_when_present()` — for each marker in `STUB_MARKERS`, body containing it → match (use `@ParameterizedTest`)
     - `user_override_extends_marker_set()` — fake `filesDir` with extra marker `"новый блок"`; body contains it; assert match
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `RknStubPageDetector`, `StubDetection`, asset loader extension
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — make match window size configurable via constructor for testing

## Definition of done

All 8 unit tests green. `RknStubPageDetector` injectable via Hilt; consumed by `add-rkn-layered-probe-pipeline`'s HTTP layer. Marker list extensible via user override.
