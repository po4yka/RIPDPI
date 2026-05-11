---
title: Add DPI Probe Suite Runner with Selection, Sequencing, and Aggregate Verdict
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: dpi-probe-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-11
---

- [ ] #task Add DPI Probe Suite Runner with Selection, Sequencing, and Aggregate Verdict #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Add `DpiProbeSuiteRunner` that orchestrates the six DPI probes (DNS integrity, DNS availability, domain reachability, TCP16 FAT, whitelist SNI, Telegram), respects user-selected probe subset, threads `stubIps` from DNS to reachability, computes the aggregate "Итог" verdict, and emits per-probe progress as a `Flow<DpiSuiteEvent>`. Surfaced as a `DpiProbeSuiteScreen` in DiagnosticsScreen Tools.

## Context

dpi-detector's `dpi_detector.py` `main()` is the suite controller: it (1) prompts the user for which tests to run via a numeric selection (`123` = run tests 1, 2, 3), (2) sequences them so that test 1's `stub_ips` feed tests 3 and 4, (3) gathers per-test stats, and (4) prints a unified `Panel("Итог")` with per-test pass/fail counts. RIPDPI needs the same orchestration logic so that (a) probes can be selectively skipped from the UI, (b) results compose into a single aggregate verdict, and (c) the user gets one summary view rather than six disjoint result tables.

**Sequencing rules (mirrors dpi-detector):**
1. **DNS integrity** runs first if selected; produces `stubIps` (set of provider stub/CGNAT IPs)
2. If DNS integrity is **not** selected but reachability or TCP16 is, run a silent `collectStubIpsSilently()` with a 5s budget so stub-IP early-exit still works
3. **DNS availability** runs in parallel with the rest (no shared state)
4. **Domain reachability** consumes `stubIps`
5. **TCP16 FAT** runs independently
6. **Whitelist SNI finder** runs only after TCP16 produces blocked-ASN results; auto-skipped if zero blocked ASNs
7. **Telegram speed test** runs in parallel with the rest

**Aggregate verdict structure (per-probe rows + overall headline):**
- DNS substitution: `× N/M подменяется` or `√ M/M OK` or `× DoH заблокирован`
- DNS availability: `N/M DoH  N/M UDP`
- Domains: `√ N/M OK · × K блок · ⏱ J таймаут (P% OK)`
- TCP 16-20KB: `√ N/M OK · × K блок · ≈ J смеш (P% OK)`
- Whitelist SNI: `<asn>: vk.com[12], gosuslugi.ru[47]` per blocked ASN
- Telegram: `Скачивание: ОК | Загрузка: ОК | Датацентры: 5/5`

**Overall headline** computed by `DpiSuiteVerdictAggregator`: takes all probe results and emits one of:
- `CLEAN` — all selected probes returned OK
- `DPI_DETECTED` — TCP16 detected blocked ASNs OR domain reachability shows blocks
- `DNS_INTERFERENCE` — DNS integrity flagged substitution/interception
- `THROTTLING` — Telegram probe returned SLOW/STALLED
- `MIXED` — multiple categories of issue
- `INCONCLUSIVE` — too many probes errored to draw a conclusion

**Per-run inputs:**
- `selection: Set<DpiProbeKind>` — which probes to run (UI checkbox row)
- `customDomains: List<String>?` — overrides bundled `domains.txt` for this run (mirrors dpi-detector `-d vk.com -d youtube.com`)
- `concurrency: Int` — overrides default `MAX_CONCURRENT` (default 100); stored in user preferences

**Reference:** `/Users/po4yka/GitRep/dpi-detector/dpi_detector.py` — `main()` (orchestration), `_format_summary` (aggregate verdict)

**RIPDPI placement:**
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiProbeSuiteRunner.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiSuiteVerdictAggregator.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiSuiteEvent.kt` — sealed event class for `Flow`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/dpi/DpiProbeSuiteScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/dpi/DpiProbeSuiteViewModel.kt`

## Acceptance criteria

- [ ] `DpiProbeKind` enum: `DNS_INTEGRITY`, `DNS_AVAILABILITY`, `DOMAIN_REACHABILITY`, `TCP16`, `WHITELIST_SNI`, `TELEGRAM`
- [ ] `DpiSuiteConfig`: `selection: Set<DpiProbeKind>`, `customDomains: List<String>?`, `concurrency: Int = 100`
- [ ] `DpiSuiteEvent` sealed class: `ProbeStarted(kind)`, `ProbeProgress(kind, completed, total)`, `ProbeCompleted(kind, result)`, `SuiteCompleted(aggregate)`
- [ ] `DpiProbeSuiteRunner.run(config): Flow<DpiSuiteEvent>` — emits events as probes progress
- [ ] Sequencing: DNS integrity → (parallel) DNS availability + (sequential) domain reachability + TCP16 → whitelist SNI (only if TCP16 found blocked ASNs) → Telegram (parallel)
- [ ] If DNS integrity is skipped but reachability/TCP16 selected: silent `collectStubIpsSilently()` with 5s budget
- [ ] `DpiSuiteVerdictAggregator.aggregate(results): SuiteVerdict (CLEAN | DPI_DETECTED | DNS_INTERFERENCE | THROTTLING | MIXED | INCONCLUSIVE)` plus per-probe summary lines
- [ ] Whitelist SNI auto-skip when TCP16 detected zero blocked ASNs
- [ ] `customDomains` overrides bundled `domains.txt` only for this run; bundled assets unchanged
- [ ] Concurrency knob persisted via `DataStore` (key `dpi.suite.concurrency`)
- [ ] `DpiProbeSuiteScreen`: probe-selection checkbox row, custom-domain text field, concurrency stepper, Run/Cancel buttons, live progress per probe, final aggregate panel
- [ ] Cancellation: tapping Cancel kills all in-flight probes via coroutine cancellation; partial results retained for display
- [ ] Unit tests: aggregator verdict matrix; sequencing with mock probes; auto-skip whitelist SNI; concurrency persistence

## TDD workflow

1. **Write tests first**:
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiSuiteVerdictAggregatorTest.kt`:
     - `clean_when_all_probes_ok()` — all six probes return success; assert `CLEAN`; fails until aggregator exists
     - `dpi_detected_when_tcp16_has_blocked_asns()` — TCP16 returns 3 blocked ASNs; others OK; assert `DPI_DETECTED`
     - `dns_interference_when_substitution_detected()` — DNS integrity flags `DNS_SUBSTITUTION`; assert `DNS_INTERFERENCE`
     - `throttling_when_telegram_stalled()` — Telegram verdict `SLOW`; assert `THROTTLING`
     - `mixed_when_dns_and_tcp16_both_blocked()` — both DNS interference + TCP16 blocks; assert `MIXED`
     - `inconclusive_when_3_of_6_probes_errored()` — half probes errored; assert `INCONCLUSIVE`
   - `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/core/diagnostics/dpi/DpiProbeSuiteRunnerTest.kt`:
     - `dns_integrity_runs_before_reachability_when_both_selected()` — instrument fakes; assert ordering via call timeline
     - `silent_stub_ip_collection_when_dns_skipped_but_reachability_selected()` — assert `collectStubIpsSilently` invoked with 5s timeout
     - `whitelist_sni_auto_skipped_when_tcp16_zero_blocked_asns()` — TCP16 mock returns all-OK; assert `WhitelistSniFinder.find` never called
     - `cancellation_propagates_to_all_inflight_probes()` — launch suite; cancel after 100ms; assert each probe's coroutine cancelled
     - `custom_domains_override_bundled_assets_for_this_run()` — pass `customDomains = ["foo.example"]`; assert `DnsIntegrityChecker.check` called with `["foo.example"]`, not bundled list
2. **Confirm red** — `./gradlew :core:diagnostics:test` — all 11 fail
3. **Implement** — `DpiProbeSuiteRunner`, `DpiSuiteVerdictAggregator`, `DpiSuiteEvent`, screen + view model
4. **Confirm green** — `./gradlew :core:diagnostics:test :app:test`
5. **Refactor** — extract sequencing DAG into a declarative `DpiProbePipeline { dns then (reach + tcp16) ... }` DSL

## Definition of done

All 11 unit tests green. `DpiProbeSuiteScreen` accessible from DiagnosticsScreen Tools section. Run-with-custom-domains flow works end-to-end. Aggregate verdict panel matches dpi-detector's "Итог" structure.
