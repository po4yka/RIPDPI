## Context

`DesyncStrategy::plan` returns `Result<(), StrategyError>`. The registry stops on every `Ok(())`, including UDP and IPv6 steps that add no action because the packet or capability is unsuitable. The proposal and delta spec define the intended behavior.

## Goals / Non-Goals

- Goal: Make handled and skipped outcomes explicit at the Rust strategy contract.
- Non-goal: Change packet execution, Lua return-value semantics, or the final TUN egress verdict API.

## Decisions

- Return `Result<StrategyPlanOutcome, StrategyError>` from `plan`, with `Applied` and `Skipped`. `Applied` means the strategy intentionally handled the flow; it does not require a byte change.
- On `Skipped`, restore the pre-step plan and continue. Keep `on_fail` for `Err` only.
- UDP and IPv6 return `Skipped` at their existing no-action branches. Other successful implementations, including Lua with no actions or explicit verdict, return `Applied` to preserve current behavior.
- Do not derive the outcome from the action count or default verdict: a successful Lua no-op is terminal, and `VERDICT_MODIFY` can represent dissect changes without emitted actions.

## Contracts and ownership

- Rust crates: `ripdpi-strategy-trait`, `ripdpi-strategy-registry`, `ripdpi-strategy-core`, `ripdpi-strategy-http`, `ripdpi-strategy-ipv6`, `ripdpi-strategy-udp`, `ripdpi-strategy-window`, `ripdpi-strategy-lua`, and the `ripdpi-desync` trait implementation.
- `ripdpi-tunnel-intercept` consumes the registry's final `StrategyVerdict`; its contract is unchanged.
- No Kotlin, JNI, wire, storage, or configuration schema changes. The Rust trait API snapshot is the only serialized shared file and has one writer.

## Risks / Trade-offs

- Public Rust trait signature break can leave implementations behind. Search all `DesyncStrategy` implementations, run affected crate tests, and regenerate/check the API snapshot.
- A skipped step could leave partial actions. Restore the checkpoint and test that later steps see the clean plan.
- Empty Lua plans could be mistaken for skips. Test a Lua no-op and explicit verdicts as terminal handled outcomes.

## Migration Plan

Change every in-workspace trait implementation and test stub in one commit. There is no persisted data migration. Rollback is a source-level revert of the contract and implementations. Run affected Cargo tests with `--locked`, Rust API snapshot check, formatting, and architecture checks before integration.
