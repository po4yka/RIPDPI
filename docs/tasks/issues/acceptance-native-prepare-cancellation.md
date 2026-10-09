---
id: TST-1791555401111874
title: Clean up compiler groups when native preparation stops
kind: bug
status: done
area: testing
priority: high
owner: Linux acceptance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-10
spec_reason: tooling-only
closed_at: "2026-10-09T21:01:38Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Clean1e51343f1: full42 plus Xray repeat1/repeat2/VM-routed PASS; combined7631 executed and staticAnalysis PASS; all5 exact published code workflows PASS; independent source/JNI/runtime review CLEAR; own AVD/Lima stopped, artifacts/evidence preserved; committed review precedes terminal record."
---

## Goal

Stop compiler process groups when native acceptance preparation is interrupted.
During preparation, the adapter started Cargo in a separate session. The signal
handlers and process ledger were installed only for runtime checks. A cancelled
preparation left the compiler group running with an orphaned parent.

This tooling fix is coordinated by acceptance task TST-1791553917096956.

## Acceptance criteria

- Preparation installs the same interruption handlers as runtime.
- The preparation process ledger owns and reaps child sessions.
- Separate SIGTERM and SIGINT regression checks prove that the compiler session
  is gone after cancellation. The adapter must return a nonzero exit code.
- All native peer driver regression tests pass. Preparation must not emit an
  acceptance pass.

## Evidence

The initial native preparation log remains local at
`build/acceptance/prepare-native-969b538bc.private.log`. The cancelled parent
left owned compiler process group 63701 running under parent PID 1. The operator
stopped that exact group and confirmed that it was gone.
The failing regression is saved in
`build/acceptance/native-prepare-cancellation-red.log`. The corrected full peer
driver test output is saved in
`build/acceptance/native-prepare-cancellation-green.log`.
