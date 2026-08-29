## Context

`ripdpi-vpn-deploy` owns the probe-matrix report JSON Schema. RIPDPI keeps a
vendored copy under `core/data` so repository contract checks can detect
producer/consumer drift without making the Android application depend on a
deployment checkout at runtime. The producer contract is frozen at
`ef688f2a785173913e6e22c42a4843f1c97451bb` for this change.

## Goals / Non-Goals

- Goal: make the vendored probe-matrix report schema byte-identical to the
  frozen schema 3 producer file and verify it through repository gates.
- Non-goal: add Kotlin or Rust schema consumers, change schema 2 window
  semantics, or modify network-exposure contracts.

## Decisions

- Copy the producer file verbatim rather than independently translating the
  schema. This keeps the producer authoritative and makes byte comparison a
  sufficient drift check.
- Limit the implementation payload to the existing test-resource schema. A
  runtime adapter or compatibility layer would create behavior absent from the
  producer contract and is outside this synchronization task.
- Treat hosted CI on the exact client commit as a separate acceptance boundary
  after local contract, task, OpenSpec, and architecture checks.

## Contracts and ownership

- No Kotlin module behavior or Rust crate behavior changes.
- The serialized shared file is
  `core/data/src/test/resources/contract/probe-matrix-report.schema.json`.
  This worktree is its sole writer for the task.
- The producer contract remains owned by `ripdpi-vpn-deploy`; RIPDPI owns only
  the vendored test-resource mirror and its validation.

## Risks / Trade-offs

- Producer revision drift could yield the wrong mirror → verify the full
  producer commit ID before copying, then compare bytes and SHA-256.
- A broad source edit could imply unsupported client behavior → inspect the
  final diff and require that no Kotlin, Rust, schema 2, or network-exposure
  files changed.
- Local success could be mistaken for cross-repository delivery → keep the
  task in review until the exact published client commit passes hosted checks.

## Migration Plan

Replace the existing vendored file atomically in the task worktree, validate
JSON and all contract mirrors, then run task/OpenSpec, architecture, and
proportional Android checks. Publish a draft PR for exact-SHA hosted checks.
There is no runtime or persisted-data migration. Rollback is a source revert of
the single mirror file and its task records; client runtime state is unchanged.
