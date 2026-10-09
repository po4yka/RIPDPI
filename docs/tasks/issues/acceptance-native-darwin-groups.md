---
id: TST-1791558393956220
title: Verify absent native peer groups after Darwin permission errors
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

Handle Darwin permission errors only when the native peer process group is
confirmed absent. A resource guard stopped native preparation. The compiler
groups were gone, but `killpg(pid, 0)` returned EPERM during ledger cleanup.
Do not treat permission errors for a live group as successful cleanup.

This follows the preparation cancellation fix in TST-1791555401111874 and is
coordinated by acceptance task TST-1791553917096956.

## Acceptance criteria

- On Darwin, a successful `ps -axo pgid=` inventory must confirm group absence
  before an EPERM probe or termination signal can be ignored.
- A live group, a failed inventory, or a permission error on another platform
  must fail cleanup.
- The full peer driver regression suite passes, including signal cancellation.
- Repeat real native preparation after host capacity is available.

## Evidence

The initial resource stop and EPERM traceback remain local in
`build/acceptance/prepare-native-4b822ee89.private.log`. The budget receipt is
`build/acceptance/native-budget-prepare-4b822ee89.json`: the guard stopped at
10.995 GiB free, above the required 10 GiB floor. Process inventory confirmed
that no owned Cargo or peer process remained.
The failing regression remains in `build/acceptance/native-darwin-group-red.log`.
All 25 peer driver tests pass after the fix; the complete output remains in
`build/acceptance/native-darwin-group-green.log`.
This unit regression is not native protocol acceptance. Full native preparation
and runtime checks still require sufficient host capacity.
