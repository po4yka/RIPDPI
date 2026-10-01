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

- Sole payload: `core/data/src/test/resources/contract/observability-topology.schema.json`.
- This task owns its task/spec records and the generated board update only.
- No Kotlin module API, Rust crate, JNI, protobuf, or stored-data contract changes.

## Risks / Trade-offs

- A schema mirror could be mistaken for runtime support: keep that boundary
  explicit in the PR and verification record.
- v1 is intentionally incompatible with v2; no shim or alternate parser is added.

## Migration Plan

Replace the schema, validate locally, publish, await exact-head required checks,
and merge normally. There is no device or stored-data migration. Rollback would
be a reviewed source revert coordinated with the producer, not a history rewrite.
