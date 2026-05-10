---
title: Integrate Probe Results with Strategy Evolver
type: task
status: review
area: engine
priority: medium
owner: Codex
parent: zapret2-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Integrate Probe Results with Strategy Evolver #repo/RIPDPI #area/engine #status/review 🔼

## Objective

Feed `StrategyProbeService` results into the UCB1 `StrategyEvolver` bandit as high-confidence prior observations, enabling the evolver to warm-start with probe-discovered winners rather than requiring live-traffic exploration.

## Context

The existing `StrategyEvolver` in `native/rust/crates/ripdpi-runtime-strategy/src/strategy_evolver/` (module directory; see `lifecycle/`, `selection.rs`, `prior_store.rs`, `thompson_sampling.rs`) uses UCB1 multi-armed bandit by default with a `StrategyCombo` (5 adaptive dimensions) and supports loading Ed25519-signed shared priors. After a strategy probe, the app has direct empirical evidence: "strategy X succeeded for domain Y with latency Z ms" — this is more valuable than the shared priors because it's measured on the current network. The integration: after probe completes, convert `ProbeReport` into UCB1 prior observations (arm = strategy_id, reward = 1.0 for success or 0.0 for failure) and inject them via a new `StrategyEvolver::inject_probe_results(results: &[ProbeResult])` method.

**Integration design:**
```rust
// In strategy_evolver/ module (e.g. lifecycle.rs) — new method:
pub fn inject_probe_results(&mut self, results: &[ProbeResult]) {
    for result in results {
        let reward = if result.success { 1.0 } else { 0.0 };
        // Inject as multiple synthetic observations (weight = 3x normal observation)
        // to give probe results higher confidence than individual live observations
        self.record_outcome(&result.strategy_id, reward, /* synthetic */ true);
    }
    // Force re-sort of UCB1 arm rankings
    self.recompute_ucb1_scores();
}
```

The Kotlin side calls `StrategyEngine.injectProbeResults(results)` via JNI after `StrategyProbeService.run()` completes.

## Acceptance criteria

- [x] `StrategyEvolver::inject_probe_results()` is implemented in Rust and accepts `&[ProbeResult]`
- [x] Injected probe results weight 3x a normal live observation (configurable constant `PROBE_OBSERVATION_WEIGHT`)
- [x] After injection, `suggest_hints()` returns the probe-winning strategy as the top suggestion for the matching domain
- [x] `recompute_ucb1_scores()` is called after injection to update rankings immediately (not deferred to next connection)
- [x] JNI method `StrategyEngine.injectProbeResults(results: Array<ProbeResultDto>)` is added and calls through to Rust
- [x] Probe results are persisted across app restarts as part of the local priors (written to the same file as shared priors but in a separate `local_priors` section)
- [x] Unit test: inject 10 success results for strategy "fake" and 0 for "split"; verify `suggest_hints()` ranks "fake" first

## Source references

- RIPDPI UCB1 evolver: `native/rust/crates/ripdpi-runtime-strategy/src/strategy_evolver/` — UCB1 bandit module; `feedback.rs` for `record_outcome()`, `selection.rs` for arm ranking, `prior_store.rs` for shared priors loading
- RIPDPI evolver JNI: the adapter that exposes StrategyEvolver to Kotlin — find in `ripdpi-android` crate
- zapret2 equivalent: zapret2 has no adaptive learning — this is a RIPDPI-original enhancement
- UCB1 algorithm: the existing implementation's arm update formula to extend

## TDD workflow

1. **Write tests first** — before implementing `inject_probe_results()`, write a Rust unit test that injects synthetic probe results and verifies UCB1 ranking changes.
2. **Confirm red** — run `cargo test -p ripdpi-runtime-strategy` and confirm the test fails because the method doesn't exist.
3. **Implement** — add `inject_probe_results()` and the local priors persistence to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions on existing UCB1 evolver tests.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-runtime-strategy/tests/inject_probe_results.rs` — inject 10 success results for strategy "fake" and 0 for "split"; call `suggest_strategy_chain()`; assert "fake" appears before "split"; fails until `inject_probe_results()` exists
- `native/rust/crates/ripdpi-runtime-strategy/tests/probe_weight_factor.rs` — inject 1 probe success (weight 3x) for "fake" vs 4 live successes for "split"; assert "fake" still ranks higher (3 > 4 live obs would fail; 3×3=9 synthetic obs > 4); fails until `PROBE_OBSERVATION_WEIGHT` constant is applied
- `native/rust/crates/ripdpi-runtime-strategy/tests/local_priors_persist.rs` — inject results, serialize local priors to a temp file, create a new `StrategyEvolver` and load the temp file; assert rankings match the original evolver's rankings; fails until persistence is implemented
- `native/rust/crates/ripdpi-runtime-strategy/tests/ucb1_recompute_immediate.rs` — inject results and immediately call `suggest_strategy_chain()` (no new connections); assert rankings updated synchronously; fails until `recompute_ucb1_scores()` is called after injection
- `app/src/androidTest/kotlin/com/poyka/ripdpi/jni/InjectProbeResultsJniTest.kt` — call `StrategyEngine.injectProbeResults(arrayOf(ProbeResultDto("fake", "youtube.com", true, 100)))` on emulator; assert no crash and JNI returns success; fails until JNI method is implemented

## Definition of done

After running a probe and injecting results, the first live connection to a probed domain uses the probe-winning strategy without any UCB1 exploration overhead. Tests were written and confirmed red before implementation began; the relevant test command is green with no regressions.

## Work log

- Added `StrategyEvolver::inject_probe_results()`, `ProbeResult`, `PROBE_OBSERVATION_WEIGHT`, local-priors save/load APIs, and a process-wide probe-result registry consumed by `StrategyEvolutionResolver` before live hint selection.
- Added JNI and Kotlin binding support via `StrategyProbeResultDto` and `StrategyEngineBindings.injectProbeResults()`.
- Wired `StrategyProbeService` to inject naturally completed probe results and avoid injection on cancellation.
- Added focused tests for weighted probe observations, immediate hint recomputation, local-priors persistence with a separate `local_priors` section, JNI facade export coverage, diagnostics injection behavior, and live runtime hint consumption.

Validation:

- `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-probe-evolver cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-strategy --test inject_probe_results --locked`
- `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-probe-evolver cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-services strategy_evolution::tests::tcp_hints_apply_injected_probe_results --locked`
- `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-probe-evolver cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-android --locked`
- `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-probe-evolver cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-strategy --test inject_probe_results --locked -- -D warnings`
- `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-probe-evolver cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-services --locked -- -D warnings`
- `CARGO_TARGET_DIR=/Users/po4yka/GitRep/.codex-targets/ripdpi-probe-evolver cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-android --locked -- -D warnings`
- `./gradlew :core:engine:ktlintCheck :core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.StrategyEngineBindingsTest -Pripdpi.skipNativeBuild=true`
- `./gradlew :core:diagnostics:ktlintCheck :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.StrategyProbeServiceTest -Pripdpi.skipNativeBuild=true`
