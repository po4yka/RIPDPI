---
title: Add Privacy-Conscious Probe Headers with Generic Chrome UA Default
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: rkn-block-checker-parity-epic
blocks: [add-rkn-layered-probe-pipeline]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Privacy-Conscious Probe Headers with Generic Chrome UA Default #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `RknProbeHeaders` that builds the HTTP header set for diagnostic probes — generic Chrome-on-Windows User-Agent + browser-like `Accept` / `Sec-Fetch-*` headers by default, with an opt-in `IDENTIFY` mode that switches to a self-identifying `RIPDPI/<version>` UA for trusted-infrastructure use.

## Context

`rkn-block-checker` v0.1 originally used `Mozilla/5.0 (RKN-Checker)` as its UA. Issue [#2](https://github.com/MayersScott/rkn-block-checker/issues/2) flagged that this is **uniquely fingerprintable** in any path-along log — including, in some jurisdictions, VPN-provider logs that get handed to regulators on request. The fix landed in v0.2: default to a generic Chrome UA with the full set of browser headers (`Accept`, `Accept-Language`, `Sec-Fetch-Dest`, etc.), and add an opt-in `--identify` flag for users who *want* to be identified as diagnostic tooling (e.g. probing infrastructure they own).

The threat model matters for RIPDPI even more than for rkn-block-checker:
1. RIPDPI is an *Android* tool — TLS ClientHello fingerprint already leaks "Android device" but the UA needn't add "running censorship-detection software"
2. RIPDPI runs longer probe suites than rkn-block-checker's one-shot (more requests = more chances for log correlation)
3. RIPDPI users may be on cellular carriers in jurisdictions where running detection tools is itself flagged

**Default header set (verbatim from `http.GENERIC_HEADERS`):**
```
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8
Accept-Language: en-US,en;q=0.9
Accept-Encoding: gzip, deflate, br
Sec-Fetch-Dest: document
Sec-Fetch-Mode: navigate
Sec-Fetch-Site: none
Sec-Fetch-User: ?1
Upgrade-Insecure-Requests: 1
```

**Identify mode UA:** `RIPDPI/<BuildConfig.VERSION_NAME> (+https://github.com/<repo>)`

**Chrome version pinning:** the literal `Chrome/147.0.0.0` will go stale. `RknProbeHeaders` should expose the Chrome version as a single constant `CHROME_UA_VERSION` and document a manual update cadence (≥ once per release cycle, or whenever the rendered version drifts more than 6 months behind upstream Chrome stable).

**Cross-cutting:** these headers are used by `add-rkn-layered-probe-pipeline`'s HTTP layer specifically. Other RIPDPI probes (TCP16, DNS) don't issue HTTP requests with browser-like semantics and should keep their existing UAs (matching their respective dpi-detector counterparts).

**Reference:** `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/http.py` (lines 17-65) + privacy reasoning in [issue #2](https://github.com/MayersScott/rkn-block-checker/issues/2)

**RIPDPI placement:**
- Headers: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknProbeHeaders.kt`
- Setting: extends `add-detection-settings-screen` with "Self-identify in diagnostic probe headers" toggle

## Acceptance criteria

- [ ] `RknProbeHeaders.build(identify: Boolean): Map<String, String>` returns the header map
- [ ] Default (`identify = false`): generic Chrome UA + 8 browser-like headers exactly matching `GENERIC_HEADERS`
- [ ] Identify mode (`identify = true`): same headers but `User-Agent` replaced with `RIPDPI/<BuildConfig.VERSION_NAME> (+<repo URL>)`
- [ ] `CHROME_UA_VERSION` constant exposed at top of file with comment documenting manual update cadence and rationale
- [ ] Setting `dpi.diagnostics.identifyInProbes: Boolean` in DataStore, default `false`; surfaced in detection settings with explainer text (privacy implications)
- [ ] Header order preserved (matters for HTTP/2 HPACK efficiency and for fingerprint blending — Chrome sends them in a specific order)
- [ ] No `Cookie`, `Referer`, or `Authorization` headers ever included (defensive — caller could add them but the default builder must not)
- [ ] Unit tests: default headers exact match; identify mode UA correct; setting flips behaviour; Chrome version constant accessible

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknProbeHeadersTest.kt`:
     - `default_user_agent_is_generic_chrome()` — `build(identify = false)`; assert `User-Agent` starts with `"Mozilla/5.0 (Windows NT 10.0; Win64; x64)"`; fails until builder exists
     - `default_includes_all_8_browser_headers()` — assert keys: `User-Agent`, `Accept`, `Accept-Language`, `Accept-Encoding`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `Sec-Fetch-User`, `Upgrade-Insecure-Requests`
     - `identify_mode_uses_ripdpi_ua()` — `build(identify = true)`; assert `User-Agent` starts with `"RIPDPI/"`
     - `identify_mode_preserves_browser_headers()` — `identify = true`; assert all 8 non-UA headers still present and unchanged
     - `header_order_matches_chrome()` — assert insertion order: UA first, then Accept, Accept-Language, Accept-Encoding, Sec-Fetch-Dest…
     - `no_cookie_referer_authorization_in_default()` — assert these keys absent
     - `chrome_version_constant_exposed()` — assert `RknProbeHeaders.CHROME_UA_VERSION` is non-blank
     - `setting_flip_changes_build_behaviour()` — toggle `identifyInProbes` setting via fake DataStore; assert builder called via DI returns identify-mode headers
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `RknProbeHeaders`, settings entry, DataStore key
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract `BROWSER_LIKE_HEADERS` constant separate from UA so swapping UA doesn't risk regressing the other headers

## Definition of done

All 8 unit tests green. Default RIPDPI diagnostic HTTP probes carry generic Chrome headers. Settings toggle exposed with privacy explainer. Chrome UA version comment documents update cadence.
