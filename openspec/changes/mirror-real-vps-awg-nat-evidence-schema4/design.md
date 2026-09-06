## Context

`core/data/src/test/resources/contract/` is the client-side location for
deployment-owned, vendored test resources. The producer commit
`c8ad0861711eb5fb63c6fad46c28c179678d51a5` adds only the evidence schema this
change mirrors; this client resource has no Kotlin or Rust runtime consumer.

## Goals / Non-Goals

- Goal: copy the producer bytes exactly and make their frozen identity
  independently checkable in the client worktree.
- Goal: record task, OpenSpec, and evidence boundaries for the schema-only
  client mirror.
- Non-goal: implement evidence production, validation semantics, signing,
  relay behavior, runtime consumption, device tests, or deployment changes.

## Decisions

- Copy bytes from the committed producer path without formatting or client-side
  interpretation; `cmp` is the drift authority and SHA-256 is recorded as
  evidence.
- Validate the JSON document and version constant with focused local commands.
  Reimplementing or copying the producer executable validator would create a
  second authority and is excluded.
- Keep the mirror as a test resource. A Kotlin parser or compatibility layer
  would be new runtime behavior outside this contract-sync task.

## Contracts and ownership

- Serialized client file:
  `core/data/src/test/resources/contract/real-vps-awg-nat-evidence.schema.json`.
- Producer file:
  `ripdpi-vpn-deploy/contract/real-vps-awg-nat-evidence.schema.json` at
  `c8ad0861711eb5fb63c6fad46c28c179678d51a5`.
- Kotlin modules, Rust crates, signer and relay paths, Android resources,
  device suites, artifacts, and deployment systems are unchanged.
- This worktree owns the mirror file and
  `DAT-1788656601400373` task/OpenSpec records only.

## Risks / Trade-offs

- Equivalent reformatted JSON would break producer synchronization; byte
  comparison rejects it.
- A producer checkout that is dirty or on another revision could misstate
  evidence; the full commit and producer-derived SHA-256 pin the source.
- Local checks cannot prove protected integration; exact-head hosted CI remains
  required after an authorized publish.

## Migration Plan

1. Verify that the absent target fails the focused identity check.
2. Copy the frozen producer bytes to the vendored resource and verify byte
   identity, JSON parsing, version constant, task/OpenSpec, and architecture
   checks.
3. Commit the schema mirror and task/OpenSpec records without publishing.
4. On producer rollback, restore this one resource to the corresponding frozen
   bytes in a new reviewed mirror change; do not retain compatibility copies.
