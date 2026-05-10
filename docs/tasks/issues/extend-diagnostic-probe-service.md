---
title: Extend Diagnostic Probe Service with Strategy Automation
type: task
status: review
area: diagnostics
priority: medium
owner: Codex
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Extend Diagnostic Probe Service with Strategy Automation #repo/RIPDPI #area/diagnostics #status/review 🔼

## Objective

Extend the existing `DiagnosticProbeService` (or equivalent diagnostics component) with a "Strategy Probe" mode that systematically tests strategy combinations against a configurable list of blocked domains, records success/failure for each combination, and outputs a ranked results table.

## Context

zapret2's `blockcheck2.sh` (`/Users/po4yka/GitRep/zapret2/blockcheck2.sh`) is a shell script that: sets up nftables, iterates over strategy combinations, tests `curl` connectivity for each, and reports which strategies bypass blocking for which domains. On Android there is no shell or nftables, but the equivalent is: for each strategy combination in `StrategyRegistry`, make an HTTPS connection through RIPDPI's proxy/VPN to a test domain, measure success (200 response or TLS established), record result, repeat for all combinations.

**Architecture:**
```
StrategyProbeService (new, runs in coroutine)
  ├── ProbeConfig: test_domains: List<String>, timeout_ms: Int, max_strategies: Int
  ├── ProbeResult: strategy_id: String, domain: String, success: Boolean, latency_ms: Long, error: String?
  ├── run(): Flow<ProbeResult>  // streaming results for live UI update
  └── summarize(results): ProbeReport  // ranked strategies by success rate
```

The probe loops over all registered strategies from `StrategyRegistry.list()`, temporarily activates each one as the sole active strategy (via the existing strategy override mechanism), makes an HTTPS connection to each test domain via `OkHttp` configured to use the RIPDPI local SOCKS5 proxy, records success/latency/error, then restores the previous strategy.

**Default test domains:** `["www.youtube.com", "www.facebook.com", "t.me", "twitter.com", "www.instagram.com"]` — user-configurable.

## Acceptance criteria

- [x] `StrategyProbeService.run()` emits `ProbeResult` for each (strategy, domain) pair as it completes — not batched at the end
- [x] Probe does not crash the app if a strategy causes a connection timeout (timeout is capped at `ProbeConfig.timeout_ms`)
- [x] Active strategy is restored to the previous value after probe completes or is cancelled
- [x] Results include `latency_ms` measured from connection start to TLS established (not full HTTP response)
- [x] DNS tampering detection: compare probe domain IP via DoH vs local DNS; flag result as `DnsTampered` if mismatch (mirrors zapret2's blockcheck DoH comparison)
- [x] Probe can be cancelled via `Job.cancel()` — all in-flight connections are aborted
- [x] `ProbeReport.ranked_strategies` returns strategies sorted by (success_rate DESC, avg_latency ASC)
- [x] Unit test with a mock SOCKS5 proxy: verify `run()` emits correct `ProbeResult` values

## Implementation notes

- Added `StrategyProbeService` in `core/diagnostics` with streaming `Flow<StrategyProbeResult>` output, built-in plus Lua strategy enumeration, settings snapshot/restore activation, SOCKS-backed OkHttp transport, DNS-over-HTTPS versus local DNS comparison, and `summarizeStrategyProbeResults()` ranking.
- Production activation persists the selected strategy into `strategy_chain_yaml` and applies parseable built-in DSL entries through the existing DataStore strategy chain fields. Lua-only candidates are discoverable through `StrategyEngineBindings.luaListStrategies()` and are persisted as YAML metadata for later native YAML/Lua execution integration.
- The requested app test files were implemented as a focused diagnostics-module test suite at `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeServiceTest.kt` because the service lives in `:core:diagnostics` and the app module should consume it rather than own the diagnostics runtime.
- The initial TDD command failed before implementation with unresolved `StrategyProbeService` symbols:
  `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.StrategyProbeServiceTest -Pripdpi.skipNativeBuild=true`.

## Validation

- `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.StrategyProbeServiceTest -Pripdpi.skipNativeBuild=true`
- `./gradlew :core:diagnostics:ktlintCheck -Pripdpi.skipNativeBuild=true`

## Source references

- zapret2 blockcheck script: `/Users/po4yka/GitRep/zapret2/blockcheck2.sh` — strategy iteration logic and test methodology
- zapret2 config strategy format: `/Users/po4yka/GitRep/zapret2/config.default` — which strategies to test
- RIPDPI existing diagnostics: `app/src/main/kotlin/com/poyka/ripdpi/` — search for Diagnostics screen or DiagnosticsViewModel
- RIPDPI SOCKS5 proxy local address: exposed through the existing settings/state (local proxy port)

## TDD workflow

1. **Write tests first** — before any implementation code, write ViewModel/service unit tests with a mock SOCKS5 proxy and mock DNS-over-HTTPS client.
2. **Confirm red** — run `./gradlew test` and confirm the unit tests fail because `StrategyProbeService` does not exist.
3. **Implement** — build `StrategyProbeService` to make the failing tests pass.
4. **Confirm green** — run `./gradlew test`; zero regressions on existing diagnostics tests.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `app/src/test/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeServiceTest.kt` — using a fake SOCKS5 proxy that always succeeds: assert `run()` emits one `ProbeResult` per (strategy, domain) pair; assert `success=true` for all; fails until `StrategyProbeService.run()` exists
- `app/src/test/kotlin/com/poyka/ripdpi/diagnostics/ProbeTimeoutTest.kt` — configure fake proxy that never responds; assert probe emits `ProbeResult(success=false, error="timeout")` after `timeout_ms` elapses; fails until timeout is handled
- `app/src/test/kotlin/com/poyka/ripdpi/diagnostics/ProbeCancellationTest.kt` — start probe, cancel the `Job` after first result; assert no more results are emitted and no exception thrown; fails until cancellation is wired
- `app/src/test/kotlin/com/poyka/ripdpi/diagnostics/DnsTamperDetectionTest.kt` — mock DoH returning IP "1.2.3.4" and local DNS returning "5.6.7.8"; assert `ProbeResult.dns_tampered = true`; fails until DoH comparison is implemented
- `app/src/test/kotlin/com/poyka/ripdpi/diagnostics/ProbeReportRankingTest.kt` — create 3 results: strategy A (5/5 success, 100ms avg), strategy B (3/5 success, 50ms avg), strategy C (5/5 success, 200ms avg); assert `summarize().ranked_strategies` order is [A, C, B]; fails until ranking comparator is correct

## Definition of done

Running a probe against 3 test domains with 5 strategy combinations completes without crash and produces a ranked report. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.
