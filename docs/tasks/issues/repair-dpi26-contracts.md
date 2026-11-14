---
id: RST-1794677647125288
title: Repair DPI-26 UDP fragmentation contracts
kind: bug
status: done
area: rust-native
priority: high
owner: Primary integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-dpi26-contracts
created: 2026-11-14
updated: 2026-11-14
closed_at: "2026-11-14T19:22:30Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Combined code SHA 520eab70b3596a7b9d472689fc1a3cfdd393acd8: 438 host tests, 8 Linux tests, 43 Kotlin tests, Clippy, rustfmt, CI source gates and independent review passed. Root/raw and device wire acceptance were not activated."
---

## Goal

Repair the three confirmed DPI-26 error, ID and root-admission contracts.

## Acceptance criteria

- Keep safe UDP error kinds and emission boundaries through IPC.
- Preserve the selected ID policy in the UDP group.
- Reject fragmentation drafts when root mode is off.
- Pass regression tests, independent review and source gates.

## Ownership

Primary integrator owns task/spec files, proxy-config ID projection, Kotlin root admission, combined tests and integration. Helper worker owns privileged UDP errors, helper formatter, client decoding, adapter fallback and their tests in an isolated worktree. Review agents are read-only. Cargo.lock, dependencies, schema versions, locales, baselines and goldens are not assigned for change.
