---
id: ENG-1794501878605236
title: Preserve TCP payload across OOB execution
kind: bug
status: done
area: engine
priority: high
owner: OOB repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi08-oob-contracts
created: 2026-11-12
updated: 2026-11-13
closed_at: "2026-11-13T06:44:27Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Seven DPI-08 repair commits up to c9d964d440 pass locked Rust tests, five-crate Clippy, just ci-gates and the owning Kotlin unit tests; a Linux run shows the prefix-pressure tests fail on the old send and pass on the fix. No packet capture, device or hosted CI run.
---

## Goal

Resolve the six DPI-08 findings. Preserve TCP payload, reject unsupported OOB parameters, and keep application byte counts accurate. Audit: `/Users/npochaev/Downloads/RIPDPI_network_audits/DPI-08.md`.

## Acceptance criteria

- Emit urgent data at most once in the first nonempty outbound TCP buffer.
- Preserve prefix progress and prevent unsafe urgent retries.
- Return ConfigError for malformed hex escapes without panic.
- Reject unsupported OOB repeat/skip variants and invalid UI byte values.
- Exclude the inserted urgent byte from application counts.
- Keep one repair commit per finding. Pass affected tests, independent review,
  and combined source gates before main integration.

## Ownership

- Runtime worker: ripdpi-desync and ripdpi-desync-runtime; F1, F2, F6.
- Parser worker: ripdpi-config/src/parse and native proxy chain validation; F3, F4.
- UI worker: Kotlin OOB validation, settings, codec and owning tests; F5.
- Integration owner: portfolio, OpenSpec, board, combined verification, integration.
- Serialized lanes: runtime files have one owner. No worker changes lockfiles,
  dependencies, protobufs, locales, baselines or generated snapshots without
  coordination. Every writer uses its own worktree.
