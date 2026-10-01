---
id: DAT-1790868146040476
title: Mirror observability topology schema v2
kind: chore
status: doing
area: data
priority: high
owner: Observability contract mirror
parent: null
blocked_by: []
spec_mode: required
openspec_change: mirror-observability-topology-v2
created: 2026-10-01
updated: 2026-10-01
---

## Goal

Unblock the resource-bounded observability producer by mirroring its topology
schema v2 from `po4yka/ripdpi-vpn-deploy` revision
`c7ef9bd519c28841f0c0b74256692fece1aa692a` without changing client runtime behavior.

## Acceptance criteria

- The topology schema is byte-identical to the frozen producer; all 31 mirrored
  contracts have no missing, differing, or orphan files.
- JSON/schema validation, task/OpenSpec validation, architecture health, locked
  Cargo metadata, and `:core:data:testDebugUnitTest` pass.
- Exact-head hosted checks pass and the authorized PR reaches protected main.

## Ownership

- Owned: `core/data/src/test/resources/contract/observability-topology.schema.json`
  and this task's OpenSpec records. The generated board is serialized to this lane.
- Out of scope: Kotlin/Rust runtime, other contract mirrors, producer code,
  credentials, network policy, deployment, and device behavior.
