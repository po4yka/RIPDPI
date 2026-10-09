---
id: CIC-1791555601071185
title: Keep task creation and drop receipts atomic
kind: bug
status: review
area: ci
priority: high
owner: Acceptance coordinator
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-09
spec_reason: tooling-only
---

## Goal

Keep task planning and duplicate-plan closure valid during local acceptance. Invalid non-epic parents and missing OpenSpec tools must fail before a task file is created. Dropping a task must not claim unrelated execution steps.

## Acceptance criteria

- Reject invalid parents and missing pinned tools before allocation or file creation.
- Record only the dropped task's owned execution IDs.
- Regenerate an affected drop receipt through taskctl while preserving terminal content hashes and validating the full portfolio.
- Run the complete taskctl test suite and repository task contracts.

## Ownership and evidence

Coordinator owns scripts/tasks/taskctl.py, scripts/tests/test_taskctl.py, this task, and the generated board in its isolated worktree. Initial regressions failed before the fix. All 36 taskctl tests pass after the fix. Local logs are retained in build/acceptance/session-20261009/taskctl-regression-*.log. Independent review is required before commit.
