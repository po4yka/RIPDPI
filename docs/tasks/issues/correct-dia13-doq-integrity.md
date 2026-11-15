---
id: DGN-1794744781459086
title: Correct DIA-13 DoQ integrity diagnostics
kind: bug
status: review
area: diagnostics
priority: high
owner: DIA-13 repair
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia13-doq-integrity
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct the seven confirmed DIA-13 findings on the Android diagnostic path for IN/A over Direct DoQ.

## Acceptance criteria

- Preserve the DNS response outcome, the comparison sources and the DoQ coverage.
- Use typed bridge failures and bounded framing, bootstrap failover and cancellation.
- Show incomplete or discrepant DoQ evidence in the DPI suite summary.
- Record one implementation commit per finding. Pass local regressions, independent review and source gates before integration.

## Ownership and serialized lanes

- Comparison, `repair-dia13-comparison` owns F1/F2 DoQ result, Kotlin parser integration, baseline inputs, minimal standalone enum maps and their tests.
- Native, `repair-dia13-native` owns Rust resolver, adapter, JNI exports and Rust tests for F4/F5/F6/F7.
- Bridge, `repair-dia13-bridge` owns Kotlin JNI adapter, request and response DTOs, and bridge tests for F4/F6/F7.
- Integration, `repair-dia13-doq` owns F3, silent preflight and shared concurrency, combined failure presentation, task files, locale and generated JNI/API inventory. It reconciles overlapping files after the lane review.
