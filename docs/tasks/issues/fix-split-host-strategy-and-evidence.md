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

## Current execution ownership — 2026-08-27

- The coordinator owns native runtime and diagnostics fixes, task/spec evidence,
  combined checks, and integration in the split-host execution worktree.
- The isolated regression writer owns service route authority production/tests
  and the non-serialized runtime-state lease contract. Native regression tests
  were integrated before production fixes; service changes use the same cycle.
- A separate packet writer owns only the existing CLI packet-smoke test file,
  including the exact Host+1 boundary oracle and its negative tests.
- After independent review, the native follow-up lane owns planner step
  provenance, runtime receipt classification, special-executor step selection,
  and focused regressions. The coordinator owns final repairs and regenerates
  the `ripdpi-desync` API snapshot through its canonical generator.
- Reviewers are read-only. Schema constants, wire contracts, goldens,
  dependency/lock files, and locale sets remain unchanged; these delegated
  assignments do not authorize schema changes or golden blessing.
- Existing implementation claims are rechecked against current source. The
  native execution step was reopened for missing failure-plan provenance and
  silently rejected trailing evidence; both now have regression coverage.
  Physical-device acceptance remains
  separate from host tests and requires permission to change device state.
