---
id: TST-1791896012189844
title: Handle zombie process groups during emulator cleanup
kind: bug
status: review
area: testing
priority: high
owner: Tooling maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-13
updated: 2026-10-13
spec_reason: tooling-only
---

## Goal

Confirm process-group shutdown when macOS reports EPERM for zombie-only groups.
Keep real permission and inspection failures as cleanup errors.

## Acceptance criteria

1. A real zombie leader reproduces the old error and is reaped by the fix.
2. Live members, failed inspection, and malformed inspection still block cleanup.
3. Shutdown checks remaining group members after SIGKILL, not only the leader.
4. Twenty SIGTERM lifecycle repetitions and the complete Android contract suite pass.
5. Harness, architecture, locked Cargo metadata, and task gates pass before integration.

## Ownership

Tooling maintainer owns `scripts/ci/android-avd-pool.py`,
`scripts/tests/test_android_avd_pool.py`, and this task's records. Review is read-only.

## Evidence

The real zombie-leader test failed with EPERM before the fix. All six new
cleanup regressions and twenty repetitions of the original SIGTERM test passed
after the fix. All 80 Android contract tests, harness, architecture, locked Cargo
metadata, and helper gates passed. Independent review found no remaining defects.
