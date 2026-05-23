# ripdpi-strategy-registry

**Responsibility:** aggregates every `ripdpi-strategy-*` implementation into an
ordered `StrategyRegistry`, builds a registry from a parsed strategy config or
from built-in technique IDs, and executes the strategy chain — the first
matching strategy wins, with a per-strategy `OnFail` policy.
**Layer:** L3 — domain logic. Keep it **thin**: an aggregator, not a strategy
host.

## How registration works

The registry is a **descriptor/factory platform**. Every strategy step is one
`StrategyStepRegistration` in the `STRATEGY_STEP_REGISTRATIONS` `linkme` slice
(from `ripdpi-strategy-trait`) — a `StrategyStepDescriptor` paired with the
`StrategyStepFactory` that builds it. Implementation crates contribute the
registrations; this crate force-links them with
`extern crate ripdpi_strategy_* as _;` so the slice entries reach the final
binary (`linkme` only collects from linked crates).

The registry resolves a step by descriptor id and dispatches through the
factory variant:

- **`Stateless`** — a zero-argument default builder (`split`, `fake`, the HTTP
  and window strategies).
- **`Configured`** — a strategy built from the parsed step's parameters,
  projected onto a schema-neutral `StrategyStepParams` (`udplen`, `ipv6_ext`).
- **`Unimplemented`** — a descriptor-only placeholder (`synack`,
  `synack_split`); the registry materializes a strategy whose `plan` fails, so
  an `OnFail::Next` chain skips past it.

There is no `BUILTIN_TECHNIQUES` table and no central `match` over step ids.

`lua` is the one special case: `ripdpi-strategy-lua` is feature-gated, so the
`lua` step is resolved directly by the registry and `LUA_STEP_DESCRIPTOR` is
registry-owned.

## Central edit points

Adding a strategy is **one** `StrategyStepRegistration` in its
`ripdpi-strategy-*` crate. The only central edit is the
`extern crate ripdpi_strategy_* as _;` force-link line here, needed solely when
the strategy lives in a *new* crate. The `descriptor_drift` and `plan_parity`
tests pin the platform; see
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§1.

## Dependency direction

**Upstream:** all `ripdpi-strategy-*` crates, `ripdpi-strategy-config`,
`ripdpi-desync`. **Downstream:** `ripdpi-tunnel-intercept` (TUN-egress strategy
execution via `StrategyRegistry::from_loaded_config` + `execute`).

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §1.
