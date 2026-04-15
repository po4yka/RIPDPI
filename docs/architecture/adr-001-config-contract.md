# ADR-001: Config Contract Authority

**Status:** Proposed | 2026-04-15
**Deciders:** RIPDPI maintainers

## Context

The proxy/runtime configuration contract is currently expressed in three
separate locations with no enforced round-trip test between them:

- `core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyChains.kt` (1305 lines)
  — Kotlin data models, validation logic, and wire-name constants.
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyJsonCodec.kt` (1494 lines)
  — Kotlin JSON serialization/deserialization of those models.
- `native/rust/crates/ripdpi-proxy-config/src/convert.rs` (1464 lines)
  — Rust conversion from the JSON payload into `ripdpi_config::RuntimeConfig`.

All three encode the same field semantics. Drift between them is the primary
source of silent misconfiguration bugs.

The three candidate authorities are:
1. Kotlin authoritative (`StrategyChains.kt` + `AppSettings` protobuf); Rust adapts.
2. Rust authoritative (`ripdpi-config` crate); Kotlin adapts.
3. Shared spec (protobuf only); both sides generate from it.

## Decision

Kotlin is the authoritative config contract owner.
`core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyChains.kt`
is the single source of truth for field names, validation rules, and defaults.
`RipDpiProxyJsonCodec.kt` is the canonical serialization layer.
The Rust crate `ripdpi-proxy-config` is a consumer that must conform to whatever
JSON the Kotlin layer produces.

## Rationale

Kotlin owns the UI, the protobuf-backed `AppSettings`, and all user-facing
validation. Making Kotlin authoritative keeps the validation logic co-located
with the data that drives it and avoids a separate spec artefact that neither
side can enforce at compile time. The Rust side already operates as a
deserializer of Kotlin-produced JSON; formalising this direction requires no
structural change, only round-trip tests to enforce the contract.

Option 2 (Rust authoritative) would require Kotlin to blindly trust a crate
it cannot import at compile time, pushing all validation downstream.
Option 3 (shared protobuf) adds a third artefact to keep in sync and requires
code-generation tooling on both sides.

## Consequences

Positive:
- Validation lives in one place (Kotlin), testable with unit tests.
- Rust `convert.rs` can be simplified to a pure deserialization adapter.
- Round-trip tests (`StrategyChains` -> JSON -> Rust parse -> re-serialize)
  become straightforward to add.

Negative:
- Rust crate cannot express config constraints independently; it must trust
  the incoming JSON has already been validated.
- Adding a new field requires touching Kotlin first, then updating `convert.rs`.

## Owner

`core/data/src/main/kotlin/com/poyka/ripdpi/data/StrategyChains.kt`
(serialization layer: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyJsonCodec.kt`)
