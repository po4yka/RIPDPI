---
title: Add SNI Compatibility Finder for Flagged-ASN Diagnostics
type: task
status: doing
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add SNI Compatibility Finder for Flagged-ASN Diagnostics #repo/RIPDPI #area/diagnostics #status/doing 🔼

## Objective

Add `AllowlistSniFinder` that, for each ASN flagged by the TCP16 probe, tests the bundled SNI compatibility list against one IP in that ASN — in batches of 5, stopping once 3 compatible SNIs are found — to identify SNI values that produce a healthy TCP16 result for the user's network path.

## Context

Android port of dpi-detector's Test 5. The diagnostic insight: some network middleboxes treat specific service SNIs differently during TLS classification. This finder enumerates those SNIs against flagged-ASN IPs and reports the values that produce a healthy TCP16 result.

**Algorithm:**
1. Filter ASNs detected by `Tcp16FatHeaderProbe` (verdict `DETECTED_AT_KB`)
2. For each flagged ASN, pick one representative IP (the one with port 443 that the TCP16 probe ran on)
3. First, run baseline `runWithRttHint` with `sni = null` (no SNI / direct IP) to capture the RTT — passed as `hint_rtt` to all SNI attempts (skips per-attempt RTT measurement, drops latency from ~5s/SNI to ~1s/SNI)
4. Iterate the bundled SNI list in 5-SNI batches; each batch runs in parallel via `async { ... }`; any SNI returning verdict `OK` from the TCP16 probe is recorded as compatible
5. Stop early once 3 working SNIs are found per ASN
6. Result: `Map<asn, List<CompatibleSni>>` where `CompatibleSni(sni: String, line: Int)` — `line` is the 1-based line number in the bundled SNI list for stable user reference

**Key reuse:** `AllowlistSniFinder` calls `Tcp16FatHeaderProbe.runWithRttHint(target, sni, hintRtt)` for each candidate. Reuse, don't duplicate.

**Reference:** local dpi-detector compatibility-list runner (lines 333-547)

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/AllowlistSniFinder.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/AllowlistSniResult.kt`

## Acceptance criteria

- [ ] `AllowlistSniResult`: `asn`, `provider`, `ip`, `compatibleSnis: List<CompatibleSni>`, `triedCount`, `aborted: Boolean (true if user cancelled)`
- [ ] `CompatibleSni(sni: String, lineNumber: Int)` — line number 1-based from the bundled SNI list
- [ ] Input: list of `Tcp16ProbeResult` with verdict `DETECTED_AT_KB` (filtered by caller)
- [ ] Baseline RTT measurement per ASN before SNI enumeration
- [ ] Batch size 5; early-stop after 3 compatible SNIs per ASN
- [ ] Reuses `Tcp16FatHeaderProbe.runWithRttHint` — no duplicate probe code
- [ ] `Map<asn, AllowlistSniResult>` returned; ASNs with zero compatible SNIs → empty `compatibleSnis` list
- [ ] Coroutine-cancellable: `withContext(Dispatchers.IO) { ... }`; user cancellation drops to the next flagged ASN cleanly
- [ ] UI: per-ASN row with "Compatible SNIs (N): example-a.test [12], example-b.test [47], example-c.test [83]"; copy-action per SNI
- [ ] Unit tests: mock `Tcp16FatHeaderProbe`; assert batch sizing, early-stop, line-number lookup

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/AllowlistSniFinderTest.kt`:
     - `early_stops_after_3_compatible_snis_found()` — mock probe returns OK for SNIs at indices 1, 3, 5; assert finder stops at index 5 (does not probe 6+); fails until finder exists
     - `tries_all_188_when_none_work()` — mock probe returns DETECTED for all; assert `triedCount == 188`
     - `batch_size_5_runs_concurrently()` — instrument mock probe; assert max 5 simultaneous calls
     - `line_number_recorded_correctly()` — bundled SNI list line 47 is `example-b.test`; mock probe returns OK only for `example-b.test`; assert `CompatibleSni("example-b.test", 47)`
     - `baseline_rtt_measured_once_per_asn()` — instrument; assert one initial probe with `sni = null`, then SNI probes use that RTT as hint
     - `cancellation_propagates_cleanly()` — launch finder in coroutine; cancel after 50ms; assert `aborted == true`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 6 fail
3. **Implement** — `AllowlistSniFinder`, integration with `Tcp16FatHeaderProbe`
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract batch-runner into `parallelMap(items, batchSize, earlyStopPredicate)` utility

## Definition of done

All 6 unit tests green. `AllowlistSniFinder` surfaced in DiagnosticsScreen Tools section as a follow-up action shown only when TCP16 probe detected flagged ASNs. Per-ASN results with copy-action per compatible SNI.
