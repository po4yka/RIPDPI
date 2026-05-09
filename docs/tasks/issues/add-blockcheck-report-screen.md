---
title: Add Blockcheck Report Screen
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: [extend-diagnostic-probe-service, integrate-probe-results-with-strategy-evolver]
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Add Blockcheck Report Screen #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Add a `BlockcheckScreen` in Kotlin/Compose that lets users run a strategy probe, view real-time results as the probe progresses, and apply the top-ranked strategy as the active config. This is the Android UI equivalent of zapret2's `blockcheck2.sh` terminal output.

## Context

The screen lives under Diagnostics → Strategy Probe (or similar path). It provides the same workflow as zapret2's blockcheck: choose test domains → run probe → view ranked results → apply best strategy. The real-time `Flow<ProbeResult>` from `StrategyProbeService.run()` updates the UI as each (strategy, domain) result comes in, so users see progress rather than waiting for completion. The final ranked table matches zapret2's `blockcheck2.sh` output format (strategy name | success count | avg latency | domains blocked).

**UI layout:**
```
Header: "Strategy Probe" | [Run] button
Test domains: editable chip list (default 5 domains, user can add/remove)
Progress bar: visible during probe run (strategies tested / total)

Results table (real-time update):
Strategy ID        | Domains OK | Avg Latency | Status
--------------------|-----------|-------------|--------
tls_split_disorder  | 5/5       | 120ms       | ✓ Best
fake_split          | 4/5       | 95ms        | ✓
wsize(4)            | 2/5       | 310ms       | ⚠ Partial
plain (no strategy) | 0/5       | —           | ✗ Blocked

[Apply Best Strategy] button — sets top-ranked as active config
[Export Report] button — shares results as text/JSON
```

**`BlockcheckViewModel`:**
```kotlin
class BlockcheckViewModel : ViewModel() {
    val probeState: StateFlow<ProbeState>  // Idle | Running | Complete | Error
    val results: StateFlow<List<ProbeResultUi>>
    val rankedReport: StateFlow<ProbeReport?>

    fun startProbe(domains: List<String>)
    fun cancelProbe()
    fun applyBestStrategy()
    fun exportReport(): String  // JSON
}
```

## Acceptance criteria

- [ ] `BlockcheckScreen` is reachable from Diagnostics (or Settings → Advanced → Strategy Probe)
- [ ] [Run] button triggers `StrategyProbeService.run()` and updates results table in real time (each `ProbeResult` emitted updates the matching row)
- [ ] Probe can be cancelled mid-run; partially complete results are displayed
- [ ] `✓ Best` badge highlights the strategy with highest success rate (tie-broken by lowest latency)
- [ ] [Apply Best Strategy] writes the winning strategy ID to `StrategyConfigDataStore` and calls `StrategyEngine.reloadConfig()` — user sees confirmation toast
- [ ] [Export Report] produces a JSON string with all `ProbeResult` entries and the ranked summary; shared via system share sheet
- [ ] Screen shows DNS tamper warning row if any domain was flagged `DnsTampered` by the probe
- [ ] Empty state: "No strategies registered" when `StrategyRegistry.list()` is empty (feature not compiled)
- [ ] Roborazzi golden for: idle state, running state (partial results), complete state

## Source references

- zapret2 blockcheck output format: `/Users/po4yka/GitRep/zapret2/blockcheck2.sh` — grep for echo/printf output lines to match the report style
- RIPDPI existing diagnostics screen: look for existing Diagnostics composable in `app/src/main/kotlin/com/poyka/ripdpi/ui/` — follow the same Compose patterns
- RIPDPI Compose theme/design tokens: existing screens for typography, color, card styles to match app visual language

## TDD workflow

1. **Write tests first** — before building the composable, write `BlockcheckViewModel` unit tests and Roborazzi screenshot goldens for each UI state.
2. **Confirm red** — run `./gradlew test` and confirm ViewModel tests fail; run `./gradlew recordRoborazziDebug` and confirm no golden exists yet.
3. **Implement** — build `BlockcheckViewModel` and `BlockcheckScreen` to make the tests pass and produce stable goldens.
4. **Confirm green** — run `./gradlew test verifyRoborazziDebug`; zero regressions on existing screen goldens.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `app/src/test/kotlin/com/poyka/ripdpi/ui/blockcheck/BlockcheckViewModelTest.kt` — assert `startProbe()` transitions state to `Running`; assert each `ProbeResult` from fake service updates `results` list; assert `cancelProbe()` transitions to `Idle`; all fail until `BlockcheckViewModel` exists
- `app/src/test/kotlin/com/poyka/ripdpi/ui/blockcheck/ProbeReportRankingUiTest.kt` — inject 3 fake results with known success rates; assert `rankedReport.value?.ranked_strategies?.first()` is the strategy with highest success rate; fails until ranking logic is wired to ViewModel
- `app/src/test/kotlin/com/poyka/ripdpi/ui/blockcheck/ApplyBestStrategyTest.kt` — call `applyBestStrategy()` when `rankedReport` is set; assert `StrategyConfigDataStore.activeStrategyId` is updated to the top-ranked strategy ID; fails until apply logic is implemented
- `app/src/test/kotlin/com/poyka/ripdpi/ui/blockcheck/ExportReportTest.kt` — call `exportReport()`; assert returned JSON string contains all `ProbeResult` entries and a `ranked_strategies` array; fails until JSON serialization is implemented
- `app/src/screenshotTest/kotlin/com/poyka/ripdpi/ui/blockcheck/BlockcheckScreenTest.kt` — Roborazzi goldens for: idle state (no results), running state (partial results table), complete state (ranked table with Best badge and Apply button); fails until composable exists

## Definition of done

Manual test: run probe against 3 domains, observe real-time row updates, press Apply Best, confirm the strategy changes in Settings → Strategy Config. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.
