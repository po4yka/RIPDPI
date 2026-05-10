---
title: Add Privacy Mode IP Masking to Detection Screen and Exports
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

- [ ] #task Add Privacy Mode IP Masking to Detection Screen and Exports #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Add a Privacy Mode toggle that masks IP addresses in all detection UI strings and export output, keeping only the first two IPv4 octets visible and hiding IPv6 groups 5–8.

## Context

RKNHardering's privacy mode prevents accidental IP leaks when sharing screenshots or exported reports. RIPDPI's `DetectionCheckScreen` currently displays raw IPs in checker result cards with no masking option.

**Masking rules (from `CheckResultExportSupport.maskIp()`):**
- IPv4: keep octets 1–2, replace octets 3–4 with `*.*` → `192.168.*.*`
- IPv6: keep groups 1–4, replace groups 5–8 with `****:****:****:****`
- Private ranges (10.x, 172.16–31.x, 192.168.x), loopback (127.x, ::1), link-local (169.254.x, fe80::) — shown **unmasked**
- `maskIpsInText()`: regex sweep of all IPv4 (`\b(?:\d{1,3}\.){3}\d{1,3}\b`) and IPv6 patterns in arbitrary strings

**Reference:** `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/CheckResultExportSupport.kt`

**RIPDPI placement:**
- New `DetectionPrivacyMask.kt` in `core/detection` — pure functions `maskIp()`, `maskIpsInText()`
- `DetectionCheckViewModel` reads `privacyMode: Boolean` from `DetectionSettings`; exposes it as part of `DetectionCheckUiState`
- All composables in `DetectionResultCards.kt`, `DetectionHistoryCommunityCards.kt` pass IPs through mask when mode is ON
- `DetectionMarkdownExportFormatter` and `DetectionJsonExportFormatter` (see `add-detection-export-share`) apply masking before serializing

## Acceptance criteria

- [ ] `maskIp(ip: String, enabled: Boolean): String` — pure function, no side effects
- [ ] `maskIpsInText(text: String, enabled: Boolean): String` — applies IPv4 + IPv6 regex sweep
- [ ] Private/loopback/link-local addresses passed through unchanged even when mode is ON
- [ ] Privacy mode toggle persisted in `DetectionSettings` DataStore; UI reacts immediately (no scan re-run needed)
- [ ] All IP strings in `DetectionCheckScreen` cards use masked output when enabled
- [ ] Export formatters apply `maskIpsInText` to all string fields when `privacyMode=true`
- [ ] Privacy mode indicator shown in screen header when active (e.g. eye-slash icon)
- [ ] Unit tests: assert all mask cases (public IPv4, public IPv6, private, loopback, link-local)

## TDD workflow

1. **Write tests first** — pure functions, no mocking needed:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/privacy/DetectionPrivacyMaskTest.kt`:
     - `public_ipv4_masks_last_two_octets()` — `maskIp("1.2.3.4", true)` == `"1.2.*.*"`; fails until function exists
     - `private_ipv4_passes_through_unmasked()` — `maskIp("192.168.1.100", true)` == `"192.168.1.100"`
     - `loopback_passes_through_unmasked()` — `maskIp("127.0.0.1", true)` == `"127.0.0.1"`
     - `public_ipv6_masks_last_four_groups()` — `maskIp("2001:db8:85a3:0:0:8a2e:370:7334", true)` ends with `****:****:****:****`
     - `link_local_ipv6_passes_through()` — `maskIp("fe80::1", true)` == `"fe80::1"`
     - `mask_disabled_returns_original()` — `maskIp("1.2.3.4", false)` == `"1.2.3.4"`
     - `mask_ips_in_text_replaces_all_public_occurrences()` — text containing two IPs; assert both masked; private IP in same text passes through
2. **Confirm red** — `./gradlew :core:detection:test` — all 7 fail (class not found)
3. **Implement** — `DetectionPrivacyMask.kt` with `maskIp()` and `maskIpsInText()`; wire into ViewModel and all card composables
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — consolidate private/loopback/link-local range checks into a single predicate

## Definition of done

Unit tests green. Enabling privacy mode immediately masks all IPs in the detection screen without re-running the scan. Exported files also contain masked IPs.
