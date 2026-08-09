---
id: CIC-1786270029481807
title: Repair published terminal-history validation
kind: bug
status: review
area: ci
priority: critical
owner: Codex tasking repair
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-09
updated: 2026-08-09
spec_reason: tooling-only
---

## Goal

Make deletion-history validation recoverable after an invalid lifecycle was already published, without weakening the required review, terminal, receipt, and purge checks for the latest task incarnation.

## Ownership

- `scripts/tasks/taskctl.py`: lifecycle-incarnation history selection.
- `scripts/tests/test_taskctl.py`: regression coverage for forward repair and invalid direct terminal re-addition.
- This task record and `docs/tasks/board.md`: serialized lifecycle evidence.

## Acceptance criteria

- Validation selects the task incarnation after the last committed absence before the deletion being checked.
- A reintroduced task must still commit `review` before `done`; direct reintroduction as `done` is rejected.
- Starting a critical portfolio task emits mdtask's supported `!crit` token.
- The published RIPDPI tasking integration history validates from `b8052b8900699589034d8a9141d769f3cd539a67` after forward repair.
- Focused taskctl tests and the full task contract gate pass.
