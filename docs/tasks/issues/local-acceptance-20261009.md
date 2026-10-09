---
id: TST-1791553917096956
title: Verify local Android and routed acceptance
kind: chore
status: review
area: testing
priority: high
owner: Acceptance coordinator
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-10
spec_reason: test-only
status_detail: Clean1e513 full42, Xray repeats/VM-routed, combined gates, independent review, cleanup and all5 published code workflows PASS; commit review before terminal closure
---

## Goal

Run the current main local acceptance catalog, fix observed defects, and publish reviewed changes to main with checked evidence.

## Acceptance criteria

- Run native, Android, routed, and packet profiles in new output directories.
- Check each report with the acceptance verifier. Keep failures and repeats separate.
- Reproduce the DNS failover recovery race before any production fix.
- Check the combined source and published CI. Report external blockers without a pass claim.

## Parallel ownership

- Android agent: app and core Android sources, Android adapter, owned emulator, and DNS fix specification. Worktree: RIPDPI-acceptance-android-20261009.
- Linux agent: acceptance VM and peer tooling, native fixture sources, owned Lima VM. Worktree: RIPDPI-acceptance-linux-20261009.
- Review agent: read-only review of code and evidence.
- Coordinator: this task, summary report, integration, and publication. Worktree: RIPDPI-acceptance-20261009.
- Cargo.lock, dependency versions, schemas, locales, baselines, and generated board have one writer assigned by the coordinator before edits. Heavy builds run in sequence through build-gate.
