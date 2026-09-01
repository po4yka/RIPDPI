## Context

The deployment repository owns the canonical contract directory and enforces a
byte-for-byte comparison against RIPDPI's vendored test resources. Two High
deployment features add seven files: three observability schemas, two
observability examples, and two network-exposure schemas.

## Goals / Non-Goals

- Goal: mirror all seven files from one exact producer commit and prove the
  complete vendored directory remains byte-identical.
- Non-goal: introduce Kotlin/Rust consumers, telemetry behavior, alerting, or
  network enforcement in the Android client.

## Decisions

- Copy producer bytes verbatim; do not translate or normalize JSON.
- Freeze one producer commit after both deployment changes share a tested tree.
- Combine the seven mirrors into one client change because they share the same
  contract-sync hard gate and have no client runtime call sites.
- Preserve the existing schema-3 probe-matrix mirror and its inherited
  requirement unchanged; it is validated with the full mirror directory but is
  not part of the seven-file change.
- Treat exact-head hosted CI as a separate acceptance boundary after local
  byte comparison and repository validation.

## Contracts and ownership

- `ripdpi-vpn-deploy/contract/` remains authoritative.
- This worktree exclusively owns the seven new files under
  `core/data/src/test/resources/contract/` and these task/OpenSpec records.
- Existing mirrors and all Kotlin/Rust runtime sources remain unchanged.

## Risks / Trade-offs

- Producer drift could mix revisions: verify the exact producer SHA and compare
  every file byte-for-byte immediately before commit and after any rebase.
- A schema-only mirror could be mistaken for runtime support: final review and
  verification explicitly require no application/native source changes.
- Combining two producer features enlarges the mirror list but saves one
  protected integration cycle without coupling their runtime behavior.

## Migration Plan

Copy the seven files from the frozen producer worktree, validate the complete
contract directory and repository gates, then publish an isolated client PR.
There is no runtime or stored-data migration. Rollback is a source revert of
the mirrors and task records.
