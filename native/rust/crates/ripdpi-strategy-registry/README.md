# ripdpi-strategy-registry

**Responsibility:** aggregates every `ripdpi-strategy-*` implementation into an
ordered `StrategyRegistry`, builds a registry from a parsed strategy config or
from built-in technique IDs, and executes the strategy chain — the first
matching strategy wins, with a per-strategy `OnFail` policy.
**Layer:** L3 — domain logic. Keep it **thin**: an aggregator, not a strategy
host.

## How registration works

Three resolution paths feed the registry:

1. **Linked factories.** `register_builtin_technique_with_policy` first looks
   up `STRATEGY_FACTORIES` (the `linkme` slice from `ripdpi-strategy-trait`).
   Implementation crates contribute factories; this crate force-links them with
   `extern crate ripdpi_strategy_* as _;` so the slice entries are present in
   the final binary (`linkme` only collects from linked crates).
2. **Built-in technique table.** When no factory matches an ID, the
   `BUILTIN_TECHNIQUES` table supplies a `BuiltinTechniqueDefinition`
   (id / label / tier / capabilities) and the `BuiltinTechnique` adapter
   plans it.
3. **Config-materialized strategies.** `configured_strategy_from_step` builds
   strategies that need per-profile parameters — `UdpLenStrategy::new(delta)`,
   `Ipv6ExtHdrStrategy::new(...)`, Lua — from a parsed `StrategyStep`.

## Central edit points

Adding a strategy the registry must resolve touches, **in this crate**:

- the `extern crate ripdpi_strategy_* as _;` list — force-link a new impl crate;
- `BUILTIN_TECHNIQUES` — a technique with no linked factory;
- `BuiltinTechnique::plan`'s `match self.definition.id` — the `DesyncAction` a
  built-in technique emits;
- `configured_strategy_from_step` — a strategy that needs config parameters.

The first path (linked factory) needs **only** the `extern crate` line — the
preferred seam. See
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§1, including the documented future refactor that would retire the
`BuiltinTechnique::plan` match.

## Dependency direction

**Upstream:** all `ripdpi-strategy-*` crates, `ripdpi-strategy-config`,
`ripdpi-desync`. **Downstream:** `ripdpi-tunnel-intercept` (TUN-egress strategy
execution via `StrategyRegistry::from_loaded_config` + `execute`).

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §1.
