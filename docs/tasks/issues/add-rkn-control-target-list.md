---
title: Bundle RKN Control Whitelist and Blacklist Target Lists
type: task
status: backlog
area: data
priority: high
owner: unassigned
parent: rkn-block-checker-parity-epic
blocks: [add-rkn-control-vs-test-aggregate-verdict]
blocked_by: [add-dpi-target-assets]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Bundle RKN Control Whitelist and Blacklist Target Lists #repo/RIPDPI #area/data #status/backlog ⏫

## Objective

Bundle two new asset files — `rkn_whitelist_control.txt` (21 should-always-work Russian sites) and `rkn_blacklist_test.txt` (15 RKN-restricted sites) — and extend `DpiAssetLoader` with typed loaders for both, supporting the same user-override mechanism as the existing `domains.txt`.

## Context

`rkn-block-checker` ships hard-coded `WHITE_URLS` (21 sites: gosuslugi, sberbank, vk, yandex, ozon, etc.) and `BLACK_URLS` (15 sites: instagram, twitter/x, meduza, protonvpn, tor-project, etc.). The whitelist is the **control group** — if these fail, the network itself is broken, not censored. The blacklist is the **test group** — failures here, with a healthy whitelist, indicate RKN/TSPU activity.

Distinct from `domains.txt` (dpi-detector's curated 40-domain list focused on DPI-mechanism testing): the rkn-checker lists are explicitly split into *should-pass* and *should-fail* halves, and the diagnostic compares the two halves' health to compute confidence.

**Lists to bundle (verbatim from `rkn_checker/targets.py`):**

Whitelist (21): `gosuslugi`, `gov.ru`, `mos.ru`, `rkn`, `nalog`, `yandex`, `yandex-maps`, `kinopoisk`, `sberbank`, `vtb`, `alfabank`, `vk`, `ok`, `ozon`, `wildberries`, `avito`, `lenta`, `rbc`, `tass`, `rutube`, `dzen`

Blacklist (15): `instagram`, `facebook`, `twitter/x`, `linkedin`, `discord`, `dailymotion`, `soap2day`, `rutracker`, `tor-project`, `protonvpn`, `deepl`, `patreon`, `bbc-russian`, `meduza`, `dw-russian`

**File format:** rkn-checker's text format — one entry per line, `name url` or bare URL (auto-name from hostname). Comments via `#`.

**Reference:** `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/targets.py` (lines 5-46)

**RIPDPI placement:**
- Assets: `core/diagnostics/src/main/assets/rkn/rkn_whitelist_control.txt`, `core/diagnostics/src/main/assets/rkn/rkn_blacklist_test.txt`
- Loader: extends `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiAssetLoader.kt`
- Models: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknTargetList.kt`

## Acceptance criteria

- [ ] Both files placed in `core/diagnostics/src/main/assets/rkn/` with the 21+15 entries from rkn-checker, preserving names and URLs verbatim
- [ ] `RknTarget(name: String, url: String, host: String)` data class — `host` derived from URL via `URI.create(url).host`
- [ ] `DpiAssetLoader.loadRknWhitelistControl(): List<RknTarget>` — parses text format; skips `#` comments; cached
- [ ] `DpiAssetLoader.loadRknBlacklistTest(): List<RknTarget>` — same; cached
- [ ] Text format parser supports: `name url`, `name=url`, bare URL with auto-name (from hostname); blank lines and `#`-comments skipped
- [ ] Auto-name derivation: `https://www.example.com/foo` → `www-example-com` (replace `.` with `-`)
- [ ] User override: `filesDir/rkn/rkn_whitelist_control.txt` takes precedence over bundled
- [ ] Duplicate name within a file → log warning, last entry wins
- [ ] Unit tests: parse text format with all three line styles; assert 21 + 15 entries; assert override precedence

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknTargetListTest.kt`:
     - `whitelist_loads_21_entries()` — assert `loadRknWhitelistControl().size == 21`; fails until loader extended
     - `blacklist_loads_15_entries()` — assert `loadRknBlacklistTest().size == 15`
     - `text_format_supports_name_url_pair()` — feed `"github https://github.com"`; assert `RknTarget("github", "https://github.com", "github.com")`
     - `text_format_supports_name_equals_url()` — feed `"custom=https://example.org"`; assert correct parse
     - `text_format_supports_bare_url_with_autoname()` — feed `"https://example.com"`; assert `name == "example-com"`
     - `comment_lines_skipped()` — feed `"# comment\nvk.com\n"`; assert single entry
     - `duplicate_name_logs_warning_last_wins()` — feed two lines with same name; assert single entry, warning logged
     - `user_override_takes_precedence()` — fake `filesDir/rkn/rkn_whitelist_control.txt`; assert override read
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Copy assets** from rkn-block-checker; implement `RknTarget`, extend `DpiAssetLoader`
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract text-format parser into `RknTargetListParser` shared with future user-supplied lists

## Definition of done

All 8 unit tests green. Both asset files present in APK (`aapt dump`). `DpiAssetLoader` exposes both loaders via Hilt singleton.
