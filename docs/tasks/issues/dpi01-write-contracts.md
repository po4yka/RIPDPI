---
id: ENG-1794498355495843
title: Repair TCP split configuration and write contracts
kind: bug
status: review
area: engine
priority: high
owner: split-contract-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi01-write-contracts
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Reject invalid split offsets, preserve partial-write evidence, and prevent replay after cancelled writes. Keep accepted payload bytes unchanged.

## Acceptance criteria

- Reject unsupported split repetition and offset suffixes at each applicable configuration boundary.
- Resolve extreme offset values without panic or arithmetic wrap.
- Preserve known write progress in fallback errors and prevent unsafe first-write replay.
- Keep the configured delay before a nonempty tail in both executors.
- Run regression tests, independent review, and required source gates before integration.

## Ownership

- split_config: ripdpi-config parsing and validation; ripdpi-proxy-config split admission; Kotlin marker admission and related tests. F1 and F4 use separate commits.
- offset_arithmetic: ripdpi-desync/src/offset.rs, dependent TLS offset adjustment, and their existing tests. F3 only.
- write_progress: desync-runtime write progress/error receipts and proxy-runtime failure classification with tests. F2 and cancellation use separate commits.
- split-contract-integration: timing parity, integration, task/OpenSpec documents, gates, and main integration.
- Shared serialized files: integrator owns task records, OpenSpec, generated board, and any API snapshot changes. No worker edits Cargo.lock, dependency versions, schemas, baselines, or golden fixtures.
- Each worker uses its assigned worktree. Review agents are read-only.

## Validation scope

Local synthetic loopback only. Physical devices, external servers, root activation, real credentials, and packet-boundary guarantees are outside this repair. Host tests do not establish external compatibility.
