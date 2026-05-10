---
title: Create ripdpi-strategy-registry crate with chain executor and UCB1 integration
type: task
status: doing
area: rust-native
priority: high
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by:
  - add-ripdpi-strategy-trait-crate
created: 2026-05-09
updated: 2026-05-10
---

- [ ] #task Create ripdpi-strategy-registry crate with chain executor and UCB1 integration #repo/RIPDPI #area/rust-native #status/doing 🔼

## Objective / Goal

Create `ripdpi-strategy-registry` crate that holds all registered `DesyncStrategy` implementations, dispatches `StrategyContext` to the right backend, chains multiple strategies with configurable `on_fail` behavior (NEXT | FALLBACK_PLAIN | DROP), and integrates with the existing `StrategyEvolver` UCB1 bandit.

## Context

The registry is the execution spine. At startup it collects all registered strategies (Rust-native via inventory/linkme pattern, config-driven via `ripdpi-strategy-config`, Lua via `ripdpi-strategy-lua`). At runtime `StrategyRegistry::execute(ctx)` runs the chain and returns `StrategyVerdict`. The UCB1 evolver in `ripdpi-runtime-strategy/src/strategy_evolver.rs` calls `suggest_hints()` — after this refactor it calls `registry.suggest_strategy_chain()` instead, which translates hints into a concrete ordered strategy list.

Key interfaces:

```rust
pub struct StrategyRegistry { ... }
impl StrategyRegistry {
    pub fn register(&mut self, strategy: Box<dyn DesyncStrategy>);
    pub fn execute(&self, ctx: &StrategyContext, plan: &mut DesyncPlan) -> StrategyVerdict;
    pub fn suggest_strategy_chain(&self, hints: &AdaptivePlannerHints) -> Vec<&str>;
}
```

Source files:
- `native/rust/crates/ripdpi-runtime-strategy/src/strategy_evolver/` — UCB1 bandit module (decomposed; see `lifecycle/`, `selection.rs`, `prior_store.rs`)
- `native/rust/crates/ripdpi-desync/src/types.rs` — `AdaptivePlannerHints` to consume
- zapret2 `lua/zapret-lib.lua` — `orchestrate()`, `verdict_aggregate()` for chain logic reference

## Acceptance criteria

- [ ] `StrategyRegistry` is `Send + Sync`
- [ ] Chain executor respects `on_fail: NEXT` by trying the next registered strategy if `plan()` returns `Err`
- [ ] `FALLBACK_PLAIN` verdict causes the connection to proceed without any desync modifications
- [ ] Registry exposes an iterator over registered strategy `StrategyDescriptor`s for diagnostics
- [ ] UCB1 bandit's `suggest_hints()` output is translated to a concrete strategy ordering in `suggest_strategy_chain()`
- [ ] `cargo test -p ripdpi-strategy-registry` covers chain-fallback and chain-success paths

## TDD workflow

1. **Write tests first** — before any implementation code, write the test(s) that cover the acceptance criteria above and verify they compile but fail for the logical reason (not a missing symbol).
2. **Confirm red** — run the targeted test command and confirm each new test fails with the expected error, not a compile error or panic unrelated to the feature.
3. **Implement** — write the minimal code to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-strategy-registry/tests/registration.rs` — register a stub strategy, call `registry.list()`, assert the stub appears; fails until `register()` and `list()` exist
- `native/rust/crates/ripdpi-strategy-registry/tests/chain_fallback.rs` — register two strategies where the first always returns `Err`; verify `execute()` with `on_fail: NEXT` calls the second strategy
- `native/rust/crates/ripdpi-strategy-registry/tests/fallback_plain.rs` — register one strategy returning `Err` with `on_fail: FALLBACK_PLAIN`; verify `execute()` returns `StrategyVerdict::Plain`
- `native/rust/crates/ripdpi-strategy-registry/tests/send_sync.rs` — `static_assertions::assert_impl_all!(StrategyRegistry: Send, Sync)`
- `native/rust/crates/ripdpi-strategy-registry/tests/ucb1_ordering.rs` — call `suggest_strategy_chain()` with mock `AdaptivePlannerHints`; assert non-empty ordered vec returned

## Definition of done

Registry compiles, tests pass, and `TcpDesyncStrategy` is registered via the inventory pattern.
Tests were written and confirmed red before implementation began; `cargo test -p ripdpi-strategy-registry` is green with no regressions.
