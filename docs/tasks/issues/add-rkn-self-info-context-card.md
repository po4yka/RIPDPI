---
title: Add Opt-In Self-Info Context Card with Public IP, ASN, and Location
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: rkn-block-checker-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Opt-In Self-Info Context Card with Public IP, ASN, and Location #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `SelfInfoFetcher` that queries `https://ipinfo.io/json` (with `https://ifconfig.co/json` fallback) for the current public IP, ASN/org, and city/region/country, and renders the result as the header card on the `RknBlockDiagnosisScreen`. Fully opt-in: default OFF; honors RIPDPI Privacy Mode.

## Context

`rkn-block-checker` prints a context header above every report:
```
  IP:       95.165.xxx.xxx
  ISP:      AS12389 Rostelecom
  Location: Moscow, Moscow, RU
```

This isn't part of the diagnosis — it's attribution context for the human reading the result. Knowing the ISP and location matters because:
- "Rostelecom in Moscow" + "TLS_BLOCK on instagram" is a known TSPU pattern
- "Tele2 in Yekaterinburg" + same blocks may indicate the regional TSPU rollout reached a different operator

Privacy concerns are sharp here:
1. `ipinfo.io` learns the user's IP at request time (already implicit when probing it)
2. The result lands in screenshots / shared diagnostic reports if the user exports
3. The IP may be redacted in display (`xxx.xxx`) but stored unredacted in JSON exports unless masked

`rkn-checker` handles this with a single CLI flag `--no-self-info` defaulting to ON-fetch. RIPDPI should invert the default to **OFF** — the user must explicitly opt in per the existing `add-detection-privacy-mode` policy.

**Reference:** `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/core.py` `get_self_info` (lines 20-27) + `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/output.py` `print_header` (lines 55-66)

**RIPDPI placement:**
- Fetcher: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/SelfInfoFetcher.kt`
- Result model: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/SelfInfoResult.kt`
- UI: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/rkn/SelfInfoCard.kt`

## Acceptance criteria

- [ ] `SelfInfoFetcher.fetch(): SelfInfoResult?` — returns `null` on any failure (no exceptions thrown to caller)
- [ ] `SelfInfoResult`: `ip: String`, `asn: String?`, `org: String?`, `city: String?`, `region: String?`, `country: String?`, `source: String` (which provider responded)
- [ ] Primary endpoint: `https://ipinfo.io/json`; 5s timeout; parses `ip`, `org`, `city`, `region`, `country`
- [ ] Fallback endpoint: `https://ifconfig.co/json` if primary returns non-2xx or times out; 5s timeout
- [ ] No retries beyond the single fallback; one-shot per diagnosis run
- [ ] Default behaviour: `enabled = false` (opt-in via setting `dpi.diagnostics.fetchSelfInfo`); when disabled, `fetch()` returns `null` immediately without network call
- [ ] Privacy Mode integration (`add-detection-privacy-mode`): when Privacy Mode ON, `fetch()` returns `null` regardless of opt-in setting (privacy override wins)
- [ ] IP masking in display: render last 2 octets as `xxx.xxx` in `SelfInfoCard` UI (matches rkn-checker's `95.165.xxx.xxx`); JSON export respects existing `add-detection-export-share` masking rules
- [ ] ASN extraction: rkn-checker's `org` field is `"AS12389 Rostelecom"`; split into `asn = "AS12389"`, `org = "Rostelecom"` when format matches; otherwise `asn = null, org = <full string>`
- [ ] Settings entry in `add-detection-settings-screen`: toggle "Fetch public IP and ASN for diagnostic context (uses ipinfo.io)" with explainer text noting the third-party endpoint
- [ ] Unit tests with `MockWebServer` for both endpoints

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/SelfInfoFetcherTest.kt`:
     - `disabled_by_default_returns_null_no_network_call()` — `enabled = false`; instrument HTTP client; assert `fetch()` returns `null` AND no requests made; fails until fetcher exists
     - `enabled_calls_ipinfo_io()` — `enabled = true`, mock returns `{"ip":"1.2.3.4","org":"AS12389 Rostelecom","city":"Moscow","region":"Moscow","country":"RU"}`; assert correct `SelfInfoResult`
     - `ipinfo_failure_falls_back_to_ifconfig()` — primary returns 503; fallback returns valid; assert result from fallback, `source == "ifconfig.co"`
     - `both_endpoints_fail_returns_null()` — both return 503; assert `null`
     - `asn_extracted_from_org_when_prefix_matches()` — input `"AS12389 Rostelecom"`; assert `asn == "AS12389"`, `org == "Rostelecom"`
     - `asn_null_when_format_does_not_match()` — input `"Some Provider Inc"`; assert `asn == null`, `org == "Some Provider Inc"`
     - `privacy_mode_overrides_opt_in()` — `enabled = true` AND `privacyMode = ON`; assert `null` returned, no network call
     - `timeout_returns_null_no_exception()` — mock hangs; assert returns `null` after 5s, no exception
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 8 fail
3. **Implement** — `SelfInfoFetcher`, `SelfInfoResult`, settings entry, `SelfInfoCard` composable
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract endpoint URLs and timeouts into `SelfInfoConfig` for testability

## Definition of done

All 8 unit tests green. `SelfInfoCard` renders above the per-target table when opt-in setting ON. Privacy Mode override verified manually. JSON export respects existing IP masking.
