---
title: Add Whitelist SNI Finder for Blocked-ASN DPI Bypass Discovery
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: [add-dpi-error-classifier, add-dpi-target-assets, add-tcp16-fat-header-dpi-probe]
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Whitelist SNI Finder for Blocked-ASN DPI Bypass Discovery #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Add `WhitelistSniFinder` that, for each ASN flagged as DPI-blocked by the TCP16 probe, brute-forces 188 Russian-domestic SNIs from `whitelist_sni.txt` against one IP in that ASN — in batches of 5, stopping once 3 working SNIs are found — to identify SNI values that bypass the DPI for the user's network.

## Context

Android port of dpi-detector's Test 5. The insight: Russian DPI implementations whitelist certain Russian-domestic SNIs (`vk.com`, `gosuslugi.ru`, `sber.ru`, `avito.ru`, ~188 in total) so that traffic with those SNIs in the TLS ClientHello bypasses inspection — even when the destination IP is a blocked CDN. This finder enumerates those SNIs against blocked-ASN IPs and reports the working ones, which the user can then plug into a `--sni` override on their VPN client to bypass the block.

**Algorithm:**
1. Filter ASNs detected as blocked by `Tcp16FatHeaderProbe` (verdict `DETECTED_AT_KB`)
2. For each blocked ASN, pick one representative IP (the one with port 443 that the TCP16 probe ran on)
3. First, run baseline `runWithRttHint` with `sni = null` (no SNI / direct IP) to capture the RTT — passed as `hint_rtt` to all SNI attempts (skips per-attempt RTT measurement, drops latency from ~5s/SNI to ~1s/SNI)
4. Iterate `whitelist_sni.txt` in 5-SNI batches; each batch runs in parallel via `async { ... }`; any SNI returning verdict `OK` from the TCP16 probe is recorded as "working"
5. Stop early once 3 working SNIs are found per ASN
6. Result: `Map<asn, List<WorkingSni>>` where `WorkingSni(sni: String, line: Int)` — `line` is the 1-based line number in `whitelist_sni.txt` for stable user reference

**Key reuse:** `WhitelistSniFinder` calls `Tcp16FatHeaderProbe.runWithRttHint(target, sni, hintRtt)` for each candidate. Reuse, don't duplicate.

**Reference:** `/Users/po4yka/GitRep/dpi-detector/cli/runners.py` — `run_whitelist_sni_test` (lines 333-547)

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/WhitelistSniFinder.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/WhitelistSniResult.kt`

## Acceptance criteria

- [ ] `WhitelistSniResult`: `asn`, `provider`, `ip`, `workingSnis: List<WorkingSni>`, `triedCount`, `aborted: Boolean (true if user cancelled)`
- [ ] `WorkingSni(sni: String, lineNumber: Int)` — line number 1-based from `whitelist_sni.txt`
- [ ] Input: list of `Tcp16ProbeResult` with verdict `DETECTED_AT_KB` (filtered by caller)
- [ ] Baseline RTT measurement per ASN before SNI enumeration
- [ ] Batch size 5; early-stop after 3 working SNIs per ASN
- [ ] Reuses `Tcp16FatHeaderProbe.runWithRttHint` — no duplicate probe code
- [ ] `Map<asn, WhitelistSniResult>` returned; ASNs with zero working SNIs → empty `workingSnis` list
- [ ] Coroutine-cancellable: `withContext(Dispatchers.IO) { ... }`; user cancellation drops to next-blocked-ASN cleanly
- [ ] UI: per-ASN row with "Working SNIs (N): vk.com [12], gosuslugi.ru [47], sber.ru [83]"; copy-action per SNI
- [ ] Unit tests: mock `Tcp16FatHeaderProbe`; assert batch sizing, early-stop, line-number lookup

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/WhitelistSniFinderTest.kt`:
     - `early_stops_after_3_working_snis_found()` — mock probe returns OK for SNIs at indices 1, 3, 5; assert finder stops at index 5 (does not probe 6+); fails until finder exists
     - `tries_all_188_when_none_work()` — mock probe returns DETECTED for all; assert `triedCount == 188`
     - `batch_size_5_runs_concurrently()` — instrument mock probe; assert max 5 simultaneous calls
     - `line_number_recorded_correctly()` — `whitelist_sni.txt` line 47 is `gosuslugi.ru`; mock probe returns OK only for `gosuslugi.ru`; assert `WorkingSni("gosuslugi.ru", 47)`
     - `baseline_rtt_measured_once_per_asn()` — instrument; assert one initial probe with `sni = null`, then SNI probes use that RTT as hint
     - `cancellation_propagates_cleanly()` — launch finder in coroutine; cancel after 50ms; assert `aborted == true`
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 6 fail
3. **Implement** — `WhitelistSniFinder`, integration with `Tcp16FatHeaderProbe`
4. **Confirm green** — `./gradlew :core:diagnostics:test`
5. **Refactor** — extract batch-runner into `parallelMap(items, batchSize, earlyStopPredicate)` utility

## Definition of done

All 6 unit tests green. `WhitelistSniFinder` surfaced in DiagnosticsScreen Tools section as a follow-up action shown only when TCP16 probe detected blocked ASNs. Per-ASN results with copy-action per working SNI.
