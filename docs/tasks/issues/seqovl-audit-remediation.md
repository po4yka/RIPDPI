---
id: ENG-1794557051102648
title: Fix seqovl execution and fallback contracts
kind: bug
status: doing
area: engine
priority: high
owner: Primary agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: seqovl-audit-remediation
created: 2026-11-13
updated: 2026-11-13
---

## Goal

Fix the seven confirmed DPI-12 findings without changing unrelated TCP methods.

## Acceptance criteria

- Seqovl flags reach its sender or fail before transmission; no plain no-op is reported as overlap.
- Safe fallback reports split, while uncertain commit remains terminal.
- All configuration inputs reject send steps after terminal seqovl.
- Oversized raw packets fall back safely; helper preserves only proven safe refusals.
- Capability includes TCP repair and a usable raw IP family.
- Each finding has a separate fix commit, regression evidence, and independent review.

## Ownership

- runtime-worker: F1 and F2; ripdpi-desync action/planner and ripdpi-desync-runtime, plus exhaustive action callers.
- config-worker: F3 and G1; Kotlin chain validation, ripdpi-config CLI, ripdpi-proxy-config validation, runtime-platform/retransmit.rs.
- privileged-worker: F5 then F4; ripdpi-privileged-ops raw seqovl sender and its pure regression tests.
- primary agent: F6; root-helper handlers and runtime-platform/root_helper_client error round-trip; planning and integration.
- Serialized lane: primary agent owns API snapshots, shared protocol files, task/OpenSpec state and generated board. No dependency, locale, golden or baseline edits are planned.
- Every writer uses its own worktree. Review agents do not edit files.
