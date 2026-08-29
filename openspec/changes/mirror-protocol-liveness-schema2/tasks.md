# DAT-1788011816707517: Mirror protocol liveness schema 2

## Objective

Vendor the frozen deployment protocol-liveness schema 2 exactly and prove
client contract compatibility without introducing runtime behavior.

## Ownership

This lane owns only
`core/data/src/test/resources/contract/protocol-liveness.schema.json` and the
task/OpenSpec evidence for `DAT-1788011816707517`. Kotlin, Rust,
network-exposure contracts, shared `main`, devices, and emulators are excluded.

## Execution

- [x] DAT-1788011957968789 Mirror schema 2 and run local contract gates #chore !high @item:DAT-1788011816707517
- [ ] DAT-1788011958473813 Publish exact client commit and verify hosted checks #chore !high @item:DAT-1788011816707517

## Verification

Required gates: exact producer SHA and 22-file byte comparison, Draft 2020-12
schema validation, schema 2 positive and schema 1 rejection checks, complete
core data tests, strict task/OpenSpec validation, architecture health,
configured hooks, and exact-head hosted CI. Device, artifact, and deployment
evidence are not applicable to this test-resource-only mirror.
