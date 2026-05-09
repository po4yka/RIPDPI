---
title: Tighten LOC baseline growth and split desync TCP tests
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Tighten LOC baseline growth and split desync TCP tests #repo/RIPDPI #area/testing #status/backlog 🔼

## Summary

Stop file-size baseline exemptions from silently growing and split the existing `ripdpi-desync-runtime/src/tcp/tests.rs` hotspot.

## Context

`check_file_loc_limits.py` reports one baseline exemption: `native/rust/crates/ripdpi-desync-runtime/src/tcp/tests.rs` now measures 1901 LOC against a 1872 baseline. The checker still exits green, so baseline-covered files can grow without forcing an explicit debt decision.

## Acceptance criteria

- [ ] Update the LOC checker to fail when a baseline-exempt file grows beyond its recorded baseline unless an explicit baseline update is made.
- [ ] Split `ripdpi-desync-runtime/src/tcp/tests.rs` by TCP strategy family or fixture domain.
- [ ] Keep each resulting test module under a sustainable LOC budget.
- [ ] Preserve all existing desync runtime test coverage.
- [ ] `python3 scripts/ci/check_file_loc_limits.py` reports no silent baseline growth.

## Completion outcome

Closing this task means LOC baselines act as real regression indicators, and the desync TCP test suite is organized by strategy family or fixture domain instead of one oversized catchall file.

## Regression guardrails

- Do not bump LOC baselines to hide growth from this refactor.
- Do not split tests into arbitrary files that still require every TCP strategy reviewer to scan all fixtures.
- Do not remove coverage just to satisfy the file-size gate.
- Do not allow baseline-exempt files to grow without an explicit failing signal or separate debt-acceptance change.
- Do not close the task without preserving or adding unit coverage for every refactored test fixture/strategy family.

## Links

- [[Epic - Post-refactor architecture cleanup]]
