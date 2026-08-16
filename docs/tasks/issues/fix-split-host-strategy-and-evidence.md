---
id: DGN-1786885244559735
title: Fix split(host+1) strategy execution and evidence
kind: bug
status: doing
area: diagnostics
priority: high
owner: Codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-split-host-strategy-and-evidence
created: 2026-08-16
updated: 2026-08-16
---

## Goal

Make diagnostics distinguish an ineffective `split(host+1)` attempt from an
unapplied, altered, incomplete, or differently routed attempt, and base the
current-strategy verdict only on evidence produced by that exact strategy.

## Acceptance criteria

- A failed candidate verdict requires a complete `baseline_current` attempt and
  a privacy-safe receipt proving the effective desync plan was applied.
- RAW_PATH connectivity and unrelated matrix candidates cannot validate or
  invalidate the active strategy.
- Partial, deadline, launch, activation, planning, and runtime failures remain
  typed incomplete or unverified evidence instead of strategy failure.
- The archive records configured-versus-effective strategy shape, observation
  path, execution disposition, bounded action/write counts, and response stage
  without domains, addresses, payload bytes, credentials, or interface names.
- Production-runtime behavioral tests cover `split(host+1)`, plain fallback,
  activation skip, runtime failure, cancellation generations, and verdict
  projection.
