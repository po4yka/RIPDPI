---
title: Wrap plan_tcp as TcpDesyncStrategy implementing DesyncStrategy trait
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: zapret2-feature-parity-epic
blocks: []
blocked_by:
  - add-ripdpi-strategy-trait-crate
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Wrap plan_tcp as TcpDesyncStrategy implementing DesyncStrategy trait #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective / Goal

Wrap the existing `plan_tcp()` function in `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` as a `TcpDesyncStrategy` struct implementing `DesyncStrategy`. The existing execution path must not change — this is a pure refactor with no behavior delta.

## Context

`plan_tcp()` currently takes `TcpChainStep` list directly and is called from `ripdpi-desync-runtime/src/tcp.rs` via `send_prepared_with_group()`. After refactor, `TcpDesyncStrategy::plan()` calls the existing function body internally. The `ActivationFilter` matching logic in `types.rs` becomes the `matches()` implementation. `send_prepared_with_group()` is updated to call through the trait instead of calling `plan_tcp()` directly, but the downstream `execute_tcp_actions()` call is unchanged.

Source files to modify:
- `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` — wrap in struct impl
- `native/rust/crates/ripdpi-desync/src/types.rs` — `ActivationFilter::matches()` becomes trait `matches()`
- `native/rust/crates/ripdpi-desync-runtime/src/tcp.rs` — call through trait
- `native/rust/Cargo.toml` — add `ripdpi-strategy-trait` dependency to `ripdpi-desync`

## Acceptance criteria

- [ ] `TcpDesyncStrategy` implements `DesyncStrategy` from `ripdpi-strategy-trait`
- [ ] All existing unit tests in `ripdpi-desync` pass unchanged
- [ ] `cargo test -p ripdpi-desync` green
- [ ] No public API surface removed from `ripdpi-desync` (callers are not broken)
- [ ] `plan_tcp()` internal function body is preserved as-is (zero behavior change)
- [ ] `describe()` returns accurate metadata: id="tcp_desync", supported L7 protocols list

## TDD workflow

1. **Write tests first** — before any implementation code, write the test(s) that cover the acceptance criteria above and verify they compile but fail for the logical reason (not a missing symbol).
2. **Confirm red** — run the targeted test command and confirm each new test fails with the expected error, not a compile error or panic unrelated to the feature.
3. **Implement** — write the minimal code to make the failing tests pass.
4. **Confirm green** — run the full crate test suite; zero regressions.
5. **Refactor** — clean up while keeping tests green.

**Test files to create before implementation:**
- `native/rust/crates/ripdpi-desync/tests/tcp_desync_strategy_trait.rs` — call `TcpDesyncStrategy.id()`, `.describe()`, and `.matches()` on a dummy `StrategyContext`; all fail until the struct exists
- `native/rust/crates/ripdpi-desync/tests/plan_parity.rs` — call `plan_tcp()` directly and via `TcpDesyncStrategy::plan()` with identical inputs; assert `DesyncPlan` outputs are equal (zero behavior delta test)
- `native/rust/crates/ripdpi-desync/tests/existing_tests_unchanged.rs` — re-run all pre-existing `plan_tcp` unit tests by name to confirm they still pass after refactor (regression guard)

## Definition of done

`cargo test -p ripdpi-desync -p ripdpi-desync-runtime` passes; diff touches only file structure, not logic.
Tests were written and confirmed red before implementation began; `cargo test -p ripdpi-desync -p ripdpi-desync-runtime` is green with no regressions.
