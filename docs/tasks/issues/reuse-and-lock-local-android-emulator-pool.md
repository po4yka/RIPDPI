---
id: TST-1791892198263799
title: Reuse and lock local Android emulator pool
kind: chore
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

Reuse compatible local Android emulators from a bounded pool. Hold an exclusive
lease for the full test command. Remove temporary devices after the command and
remove confirmed idle legacy RIPDPI devices without changing active devices.

## Acceptance criteria

1. Repeated compatible runs use the same AVD; the default pool has at most two slots.
2. Concurrent runs cannot lease the same AVD or emulator port.
3. Temporary devices require a reason and are removed after success, failure, or a handled signal.
4. Cleanup is a dry run by default and skips active, leased, foreign, and unsafe paths.
5. CI recreation is explicit; local instructions use the pool wrapper.
6. Lifecycle regression tests and `just harness-check` pass.
7. The four idle dated RIPDPI devices are removed; the active Readability device is retained.

## Ownership

- Pool worker: `scripts/ci/android-avd-pool.py` and `scripts/tests/test_android_avd_pool.py`, in its own worktree.
- Tooling maintainer: existing emulator scripts, CI callers, instructions, documentation, task records, combined validation, and host cleanup.
- No shared file has multiple writers. The review agent is read-only.

## Observed evidence

- Local Android result contract selection: 74 tests passed, including all 24 pool lifecycle tests.
- `just harness-check`, ShellCheck, actionlint, Python compilation, and task validation passed.
- Independent review found no remaining actionable defects after the port-lifetime, offline-device, and shutdown fixes.
- Two live API 37 ARM64 runs reused `RIPDPI_Pool_1`. Creation metadata and the userdata disk inode were unchanged.
- A live temporary AVD was removed after its command returned 7. Its disk, pointer, and ownership metadata were absent afterward.
- Cleanup removed Acceptance, LocalAcceptance, Docs, and PlayVisual dated AVDs with 17,713,135,616 allocated bytes in total. The active Readability AVD was preserved.
- The first live boot exposed a transient ADB timeout. The final readiness logic retries that probe within the boot deadline; the subsequent live runs passed.
- These are local tool and emulator lifecycle checks. Hosted CI and application acceptance were not run.
- Detailed local logs and before/after records are retained in `.tmp/avd-pool-validation/` in the job worktree.
