---
id: CIC-1788604348639886
title: Expose installed Android platform tools to CI steps
kind: bug
status: doing
area: ci
priority: high
owner: Audit integration
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-05
updated: 2026-09-05
spec_reason: tooling-only
---

## Goal

Make installed adb available to later CI steps. All five instrumentation jobs in run 33958739073 failed before test execution because adb was absent from PATH.

## Acceptance criteria

- Publish the installed SDK platform-tools directory through GITHUB_PATH.
- Reuse SDK discovery and fail if adb is not executable.
- Verify a separate consumer process with adb initially absent from PATH.
- Pass workflow and harness checks, then hosted Android instrumentation.

## Ownership

Audit integration owns the shared setup action, its existing executable CI contract tests and this record in the main-candidate worktree. Review agents are read-only. No gates or dependencies change.
