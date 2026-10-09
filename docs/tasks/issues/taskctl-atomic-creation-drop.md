---
id: CIC-1791555601071185
title: Keep task creation and drop receipts atomic
kind: bug
status: done
area: ci
priority: high
owner: Acceptance coordinator
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-10
spec_reason: tooling-only
closed_at: "2026-10-09T21:01:40Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Clean1e51343f1: full42 plus Xray repeat1/repeat2/VM-routed PASS; combined7631 executed and staticAnalysis PASS; all5 exact published code workflows PASS; independent source/JNI/runtime review CLEAR; own AVD/Lima stopped, artifacts/evidence preserved; committed review precedes terminal record."
---

## Goal

Keep task planning and duplicate-plan closure valid during local acceptance. Invalid non-epic parents and missing OpenSpec tools must fail before a task file is created. Dropping a task must not claim unrelated execution steps.

## Acceptance criteria

- Reject invalid parents and missing pinned tools before allocation or file creation.
- Record only the dropped task's owned execution IDs.
- Regenerate an affected drop receipt through taskctl while preserving terminal content hashes and validating the full portfolio.
- Accept only the pinned OpenSpec zero-checkbox warning for dropped execution with valid terminal receipts; keep strict validation for all other results.
- Run the complete taskctl test suite and repository task contracts.

## Ownership and evidence

Coordinator owns scripts/tasks/taskctl.py, scripts/tests/test_taskctl.py, this task, and the generated board in its isolated worktree. Initial regressions failed before the fix. All 36 initial taskctl tests pass after the first fix. A dropped OpenSpec plan then exposed the pinned tool's zero-checkbox warning. The full suite now has 38 passing tests, including rejection paths for active changes, other issues, and malformed output. Local logs are retained in build/acceptance/session-20261009/taskctl-regression-*.log. Independent review is required before commit.
