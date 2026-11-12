---
id: RST-1794499062183726
title: Repair hostfake payload and execution contracts
kind: bug
status: review
area: rust-native
priority: high
owner: DPI-07 repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-hostfake-audit-contracts
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Repair DPI-07 F1–F6, state the raw requirement in settings and prevent H1 late-error replay.

## Acceptance criteria

- Correct receipts, fake-only MD5, modulo sequence arithmetic and fragmented SNI framing.
- Reject malformed templates consistently and retain random mode through Android configuration.
- Late raw failures are terminal. Real bytes and subsequent chain steps remain intact.
- Separate fix commits, owner regressions, independent review, staticAnalysis, ci-gates, architecture health and locked Cargo metadata.
- No root/external probes or golden blessing.

## Ownership

- Parent: task/OpenSpec state, F1 runtime receipt and UI, all locales, generated API/contract exports and combined integration. Parent also owns H1 runtime no-replay test.
- Raw worker: ripdpi-privileged-ops ordered sender/tests F2, sequence/tests F3, late-error handling/tests H1; three commits.
- TLS worker: ripdpi-desync fake/span/planner/tests and desync-runtime HostFake construction/tests F4. Shared hostfake.rs edits only the constructor call; parent owns fallback block.
- Config worker: ripdpi-config validation and native config tests; Kotlin chain model/validation/DSL/proto, settings backup, engine codecs and tests F5/F6; two commits.
- Serialized lanes: config worker alone owns app_settings.proto; parent alone owns locales, generated snapshots, portfolio/OpenSpec and baselines. Cargo.lock, versions and golden fixtures stay unchanged.
- Every writer has an isolated worktree. Preserve others' edits. Review agents are read-only.
