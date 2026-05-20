# ripdpi-strategy-config

**Responsibility:** the strategy-chain config model — parses developer- and
CLI-authored YAML or TOML strategy files into a `LoadedStrategyConfig` (a list
of `LoadedStrategy`, each a matcher plus ordered `StrategyStep`s), resolves
`@file` host-list references, and hot-reloads on file change
(`StrategyConfigReloader`).
**Layer:** L2 — contracts / config.

## Stable identifiers / contracts

- `StepType` — the strategy step-kind enum. Its serde representation **is** the
  YAML/TOML schema; the `#[serde(rename = ...)]` / `alias` attributes are a
  config-schema contract. `registry_id()` maps each variant to the stable
  string ID that `ripdpi-strategy-registry` resolves — that mapping must stay
  in lock-step with the registry's IDs.
- `OnFail`, `ProtocolName`, and the `StrategyStep` field set are likewise
  schema. Renaming a variant, changing a `rename`/`alias`, or adding/removing a
  `StrategyStep` field changes the config schema.

## Not the Android settings path

This crate is **not** the Android protobuf settings surface. Kotlin's
`StrategyChain{Model,Parser,Protobuf,Validation,Dsl}.kt` map the proxy-mode
`StrategyTcpStep.kind` string surface (see
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§1, "Files / crates likely touched"). This crate's YAML/TOML config is the
**file-driven** strategy surface (CLI / TUN-egress) consumed through
`ripdpi-strategy-registry`. The two are separate config inputs that converge on
the same `DesyncStrategy` contract.

## Dependency direction

**Upstream:** none (leaf crate). **Downstream:** `ripdpi-strategy-registry`
(`StrategyRegistry::from_loaded_config`), `ripdpi-android` (directly).

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §1.
