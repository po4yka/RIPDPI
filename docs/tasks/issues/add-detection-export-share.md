---
title: Add Detection Result Export in Markdown and JSON Formats
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: [add-ip-consensus-synthesis, upgrade-verdict-engine-rules-matrix]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Detection Result Export in Markdown and JSON Formats #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Add Markdown and JSON export formatters for `DetectionCheckResult`, surfaced via a share action in `DetectionCheckScreen`. Output respects the privacy mode IP masking setting.

## Context

RIPDPI already has `DetectionReportFormatter` (plain text). RKNHardering exports two richer formats consumed by support channels and community comparisons. These need to be ported so RIPDPI users can share structured reports.

**Filename pattern:** `ripdpi-detection-yyyy-MM-dd_HH-mm-ss.{md|json}`

**Markdown structure (from `CheckResultMarkdownExportFormatter`):**
1. `# RipDPI Detection Report` header
2. Summary code block: VERDICT, EXPOSURE, PRIVACY MODE, TIMESTAMP
3. Verdict section: status, explanation, "What this means", "What was discovered", "Why this verdict"
4. Section summary table: `| Section | Status | Summary |` for all checker categories
5. Per-checker detail sections — GeoIp (5 providers), IpComparison (RU/NON_RU groups), CdnPulling (per-endpoint with rawBody), DirectSigns, IndirectSigns, NativeSigns, IcmpSpoofing, RttTriangulation, LocationSignals, IpChannels table, TUN probe section, Bypass section
6. Bypass section: proxy endpoint, owner app, direct/proxy/VPN/underlying IPs, Xray outbounds (tag, protocol, address, port, SNI, senderSettingsType, proxySettingsType, uuidPresent, publicKeyPresent)
7. Footer: timestamp, app version, build type, privacy mode
8. Section tags: `[OK]`, `[REVIEW]`, `[DETECTED]`, `[ERROR]`

**JSON structure (from `CheckResultJsonExportFormatter`):**
- `meta`: formatVersion, timestamp, appVersion, buildType, privacyMode
- `verdict`: value, status, explanation, exposureStatus, meaning[], discovered[], reasons[], homeRoutedRoaming?, roamingDiagnostics{}
- `results`: per-checker objects (detected, needsReview, hasError, findings[], evidence[], matchedApps[], callTransportLeaks[], stunProbeGroups[], geoFacts?, locationFacts?)
- `ipConsensus`: per-channel IP map
- `tunProbeDiagnostics`: full TUN probe detail

**Reference formatters:**
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/CheckResultMarkdownExportFormatter.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/CheckResultJsonExportFormatter.kt`

**Privacy masking:** when Privacy Mode is ON, all IPv4 (`\b(?:\d{1,3}\.){3}\d{1,3}\b`) and IPv6 patterns in output strings are masked (last 2 octets → `*.*`; IPv6 groups 5–8 → `****`). Private/loopback/link-local IPs shown unmasked.

**RIPDPI placement:** extend `core/detection` — new `export/DetectionMarkdownExportFormatter.kt` and `export/DetectionJsonExportFormatter.kt`. Share via `DetectionCheckViewModel.shareReport()` → `Intent.ACTION_SEND`.

## Acceptance criteria

- [ ] Format selection dialog (Markdown / JSON) shown on share button tap; debug mode adds "Copy to clipboard" option
- [ ] Markdown output matches section structure above; all checker categories included
- [ ] JSON `formatVersion=1`; all `results` keys present even when checker was skipped (nulls acceptable)
- [ ] Privacy mode IP masking applied to every string field in both formats; private/loopback IPs unmasked
- [ ] Xray outbound fields: `uuidPresent` and `publicKeyPresent` are booleans (never expose actual values)
- [ ] File written to cache dir + shared via `FileProvider`; no storage permission needed
- [ ] Unit tests: serialize a known `DetectionCheckResult` fixture; assert key fields present; assert IP masking; assert Xray credential fields are boolean-only

## TDD workflow

1. **Write tests first** — use a known `DetectionCheckResult` fixture (build a minimal one in a test helper):
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/export/DetectionMarkdownExportFormatterTest.kt`:
     - `output_contains_summary_code_block()` — assert output contains ```` ```\nVERDICT: ````; fails until formatter exists
     - `output_contains_all_required_section_headers()` — assert `## GeoIp`, `## IpComparison`, `## Bypass` etc. all present
     - `xray_uuid_not_present_in_output()` — inject fixture with a real UUID; assert output does NOT contain the UUID string (only `uuidPresent: true`)
     - `privacy_mode_masks_ips_in_markdown_output()` — set `privacyMode=true`; inject fixture with IP `5.6.7.8`; assert output contains `5.6.*.*`
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/export/DetectionJsonExportFormatterTest.kt`:
     - `json_contains_required_top_level_keys()` — parse output; assert `meta`, `verdict`, `results`, `ipConsensus` keys present
     - `format_version_is_1()` — assert `meta.formatVersion == 1`
     - `xray_public_key_not_exposed()` — inject fixture with a real key; assert JSON does NOT contain key value (only `publicKeyPresent: true`)
     - `privacy_mode_masks_ips_in_json_string_fields()` — set `privacyMode=true`; assert no unmasked public IP in serialized JSON
2. **Confirm red** — `./gradlew :core:detection:test` — all 8 fail
3. **Implement** — `DetectionMarkdownExportFormatter`, `DetectionJsonExportFormatter`, share action in `DetectionCheckViewModel`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract section renderers; share fixture builder with other test classes

## Definition of done

Unit tests green. Share sheet opens with correctly formatted file. Privacy mode masks IPs in the exported file.
