---
id: TRN-1794371392084141
title: Fix finalmask audit findings
kind: bug
status: doing
area: transport
priority: high
owner: Finalmask audit fixes
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-finalmask-audit-findings
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Fix TRN-06 findings F1–F6 and define the supported peer subset without new protocol features.

## Acceptance criteria

- Bound fragment memory, preserve Sudoku password bytes, and release failed carriers.
- Reject invalid active settings before carrier use and retain unknown types for rejection.
- Publish exact reference pins and valid peer examples with explicit compatibility limits.
- Observe targeted regression tests, independent review, staticAnalysis and just ci-gates before integration.


## Ownership

- Native validation writer: ripdpi-xhttp finalmask spec.rs, fragmentation.rs, sudoku.rs, tests.rs and relay-core runtime_validation.rs. F1, F2 and F4 each use a separate commit.
- Bridge writer: ripdpi-xhttp finalmask bridge.rs and a separate bridge test module. F3 only.
- Kotlin writer: finalmask normalization, settings import, service and draft validation plus their owning tests. F5 only. No locale keys or schema fields.
- Integrator: docs/native/finalmask-compatibility.md, ripdpi-xhttp SPEC files, docs/examples/finalmask, peer acceptance assets, portfolio/OpenSpec and generated board. F6 and related peer gaps.
- Serialized lanes: native spec.rs/tests.rs/runtime_validation.rs have one writer. Locale sets, lockfiles, protobuf, wire.rs, baselines and golden fixtures are not changed. The integrator alone updates API snapshots through owning generators if required.
- Every writer uses a separate worktree. No worker integrates or pushes.
