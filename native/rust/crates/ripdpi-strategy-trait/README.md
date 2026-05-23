# ripdpi-strategy-trait

**Responsibility:** the strategy contract — the `DesyncStrategy` trait every
desync backend implements, the `StrategyContext` / `DesyncPlan` / `DesyncAction`
types passed across it, and the two `linkme` distributed slices through which
strategy crates advertise themselves to the registry.
**Layer:** L2 — contracts / config.

## Stable identifiers / contracts

- `DesyncStrategy` — `id` / `matches` / `plan` / `describe`. Implemented by the
  built-in `ripdpi-strategy-*` crates, by config-materialized strategies, and
  by Lua strategies.
- `STRATEGY_STEP_REGISTRATIONS` — a `linkme::distributed_slice` of
  `StrategyStepRegistration` entries, each a `StrategyStepDescriptor` paired
  with a `StrategyStepFactory` (`Stateless` / `Configured` / `Unimplemented`).
  A strategy crate contributes one registration here so
  `ripdpi-strategy-registry` resolves it by descriptor id with **no central
  match arm**.
- `STRATEGY_DESCRIPTOR_REGISTRATIONS` — the sibling slice for planners (the
  `tcp_desync` chain planner) that are not config-step kinds but should still
  be visible to diagnostics / inventory checks.
- `StrategyStepDescriptor`, `StrategyStepParams` — the step-kind metadata and
  the schema-neutral parameter bag the configured-strategy factories consume.
- `StrategyDescriptor`, `CapabilityTier`, `RuntimeCapability` — the metadata a
  strategy advertises and the runtime gates it on.

This is a hand-authored contract trait — **do not auto-generate it**. A change
to the trait or its types ripples to all 10 `ripdpi-strategy-*` crates and is a
breaking ABI change (see `NATIVE_RUST.md` §5).

## Dependency direction

**Upstream:** none (leaf crate). **Downstream:** every `ripdpi-strategy-*`
implementation crate, `ripdpi-strategy-registry`, and `ripdpi-desync`
(fan-in 10).

## Adding a new strategy

See [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§1 — "The strategy registration seam". `ripdpi-strategy-window` is the smallest
worked example of an implementation crate.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §1.
