## Context

The producer owns the observability schema; RIPDPI keeps its exact test-resource
mirror. Source inspection found no Kotlin or Rust consumers of this topology.

## Goals / Non-Goals

- Goal: clear contract-sync drift for resource-bounded observability.
- Non-goal: add an Android observer, parser, backend, or runtime dependency.

## Decisions

- Copy the frozen schema exactly, without reformatting or compatibility fields.
- Validate the entire 31-file mirror, JSON and Draft 2020-12 validity, task/spec
  records, architecture health, locked Cargo metadata, and core data unit tests.
- Publish through a protected-main PR; the producer checks the consumer's default
  branch, so a consumer feature branch alone does not clear its gate.

## Contracts and ownership

- Mirror payload: `core/data/src/test/resources/contract/observability-topology.schema.json`.
- The golden-blesser lane owns the authorized CI repair in the API snapshot
  checker, its regression test, and only the strategy-trait API snapshot.
- This task also owns its task/spec records and generated board update.
- No Kotlin module API, Rust crate, JNI, protobuf, or stored-data contract changes.

The failing hosted check exposes two pre-existing issues: nightly rustdoc emits
`alloc::rcs::arc::Arc` for the canonical `alloc::sync::Arc` type, and the
strategy-trait snapshot omits `Dissect::tcp_mss: Option<u16>`, already present in
base revision `53124991ec1e573ac3ed94b0aa97ba5add26af59`. Normalize the alias
without changing those snapshots; regenerate only the strategy-trait snapshot
through the checker's owning function. Tests must still reject changed Arc type
arguments and newly added public fields.

## Risks / Trade-offs

- A schema mirror could be mistaken for runtime support: keep that boundary
  explicit in the PR and verification record.
- v1 is intentionally incompatible with v2; no shim or alternate parser is added.

## Migration Plan

Replace the schema, validate locally, publish, await exact-head required checks,
and merge normally. There is no device or stored-data migration. Rollback would
be a reviewed source revert coordinated with the producer, not a history rewrite.
