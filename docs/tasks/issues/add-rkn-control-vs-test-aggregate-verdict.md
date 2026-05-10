---
title: Add Control-vs-Test Aggregate Verdict for RKN Block Diagnosis
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: rkn-block-checker-parity-epic
blocks: []
blocked_by: [add-rkn-control-target-list, add-rkn-layered-probe-pipeline]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Control-vs-Test Aggregate Verdict for RKN Block Diagnosis #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `RknAggregateVerdictEngine` that, given a whitelist (control) and blacklist (test) of `RknCheckResult`s, returns a single `RknAggregateVerdict` with one of six headlines and a confidence note explaining why. Refuses to claim "blocked" when the whitelist itself is failing — the diagnostic is only meaningful with a working baseline.

## Context

This is the Android port of `rkn_checker/output.py` `_summary_verdict` (lines 139-201). The function's defining property is **control-awareness**: the rules tier the headline by *both* blacklist failure rate *and* whitelist health, so the diagnostic refuses to mistake "your uplink is broken" for "you're being censored". This is the most important downstream check in the rkn-block-checker pipeline — without it, a misconfigured DNS or a bad WiFi network produces false-positive censorship reports.

**Decision tree (verbatim from `_summary_verdict`):**

1. **Whitelist `<` 50% working** → `INCONCLUSIVE_CONTROL_DOWN`
   - Note: "Can't separate censorship from a broken uplink without a working baseline. Try a different network, or check the local connection."

2. **All blacklist probes timed out** (effective_total = 0 after subtracting timeouts) → `INCONCLUSIVE_ALL_TIMEOUT`
   - Note: "Cannot determine blocking status when every probe times out."

3. **Blacklist 100% OK** → `NOT_BLOCKED_OR_VPN`
   - Note: "All blacklisted sites loaded — either you're outside the blocked zone, or your VPN/proxy is intercepting the traffic."

4. **Blacklist `≥` 70% blocked AND `≥` 50% of those are HIGH-confidence** → `IN_BLOCKED_ZONE_HIGH`
   - Note: "{N}/{M} blacklist failures match high-confidence patterns (DNS poisoning confirmed by DoH, HTTP 451, known stub-page markers)."

5. **Blacklist `≥` 70% blocked, but mostly MEDIUM/LOW confidence** → `IN_BLOCKED_ZONE_MEDIUM`
   - Note: "Most blacklist failures match censorship patterns (TLS DPI, TCP RST), but those signals can also be caused by server-side issues. A control vantage point would confirm."

6. **Blacklist 1-69% blocked** → `PARTIAL_BLOCKS`
   - Note: "Mixed signals. May indicate selective filtering, a mix of real blocks and unrelated server issues, or a CDN flake."

The function also produces a per-block-type tally for the report:

- `Block types in the blacklist: ✗ DNS: 2, ~ LIKELY TLS DPI: 8, ✗ HTTP STUB: 2`

**Verdict labels in UI** (matches `output._label_for`):
- HIGH confidence: `✗ <type>` (e.g. `✗ DNS`, `✗ HTTP STUB`)
- MEDIUM confidence: `~ LIKELY <type>` (e.g. `~ LIKELY TLS DPI`)
- LOW confidence: `? <type>?`
- OK: `✓ OK`
- DOWN: `· DOWN`
- UNKNOWN: `? UNKNOWN`

**Reference:** `/Users/po4yka/GitRep/rkn-block-checker/rkn_checker/output.py` `_summary_verdict` (lines 139-201) and `_label_for` (lines 32-52)

**RIPDPI placement:**
- Engine: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknAggregateVerdictEngine.kt`
- Verdict model: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknAggregateVerdict.kt`
- UI: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/rkn/RknBlockDiagnosisScreen.kt`

## Acceptance criteria

- [ ] `RknAggregateVerdict` sealed class with 6 variants: `InconclusiveControlDown`, `InconclusiveAllTimeout`, `NotBlockedOrVpn`, `InBlockedZoneHigh`, `InBlockedZoneMedium`, `PartialBlocks`
- [ ] Each variant carries `headline: String` and `confidenceNote: String` matching the rkn-checker copy verbatim
- [ ] `RknAggregateVerdictEngine.aggregate(whitelist: List<RknCheckResult>, blacklist: List<RknCheckResult>): RknAggregateVerdict`
- [ ] Whitelist-health gate: if `whiteOk < whiteTotal / 2` → return `InconclusiveControlDown` regardless of blacklist
- [ ] All-timeout gate: if `blackTotal - blackTimeout == 0` → return `InconclusiveAllTimeout`
- [ ] HIGH-confidence threshold: `IN_BLOCKED_ZONE_HIGH` requires both `≥ 70%` blocked **and** `≥ 50%` of effective_total being HIGH-confidence blocked
- [ ] Block-type tally: `blockTypes(blacklist): Map<RknVerdict, Int>` — counts each blocked verdict type for the UI breakdown
- [ ] Verdict label mapping: `RknVerdictLabel.format(verdict, confidence): VerdictLabel(symbol: String, text: String, color: ColorToken)` matching the `_label_for` table
- [ ] Edge case: empty whitelist → skip control-down gate (no control to compare against); fall through to other rules
- [ ] Unit tests cover all 6 verdict branches plus edge cases (empty lists, mixed timeouts, exactly-at-threshold percentages)

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/rkn/RknAggregateVerdictEngineTest.kt`:
     - `whitelist_below_half_returns_inconclusive_control_down()` — 21 whitelist with 5 OK, blacklist all blocked; assert `InconclusiveControlDown`; fails until engine exists
     - `all_blacklist_timeout_returns_inconclusive_all_timeout()` — whitelist all OK, blacklist all `TIMEOUT`; assert `InconclusiveAllTimeout`
     - `blacklist_all_ok_returns_not_blocked_or_vpn()` — both lists fully OK; assert `NotBlockedOrVpn`
     - `seventy_percent_blocked_with_majority_high_returns_high_confidence()` — 21 white OK, 15 black with 11 blocked of which 8 HIGH; assert `InBlockedZoneHigh`
     - `seventy_percent_blocked_mostly_medium_returns_medium_confidence()` — 11 blocked, all MEDIUM; assert `InBlockedZoneMedium`
     - `partial_blocks_returns_partial()` — 5/15 blocked; assert `PartialBlocks`
     - `confidence_note_quotes_high_count()` — assert `IN_BLOCKED_ZONE_HIGH` note contains `"8/15"` for that scenario
     - `block_type_tally_counts_per_verdict()` — blacklist with 3 `DNS_BLOCK` + 5 `TLS_BLOCK` + 2 `HTTP_STUB`; assert `blockTypes` returns `{DNS_BLOCK: 3, TLS_BLOCK: 5, HTTP_STUB: 2}`
     - `empty_whitelist_skips_control_down_gate()` — empty whitelist, blacklist all blocked HIGH; assert `InBlockedZoneHigh` (not `InconclusiveControlDown`)
     - `verdict_label_high_uses_x_symbol()` — `format(DNS_BLOCK, HIGH)`; assert `symbol == "✗"`, text `== "DNS"`
     - `verdict_label_medium_uses_likely_prefix()` — `format(TLS_BLOCK, MEDIUM)`; assert text `== "LIKELY TLS DPI"`
     - `boundary_seventy_percent_inclusive()` — exactly 70% blocked; assert ≥ 70% rule fires (not just > 70%)
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 12 fail
3. **Implement** — `RknAggregateVerdictEngine`, `RknAggregateVerdict` sealed class, `RknVerdictLabel.format`
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract threshold constants (`CONTROL_HEALTH_MIN = 0.5`, `BLOCKED_THRESHOLD = 0.7`, `HIGH_CONF_THRESHOLD = 0.5`) into a documented `RknThresholds` object

## Definition of done

All 12 unit tests green. `RknBlockDiagnosisScreen` renders the headline + confidence note + block-type tally + per-target table. Aggregate verdict logic exactly matches rkn-checker's decision tree.
