---
id: DGN-1786481385799433
title: Preserve diagnostic startup logs in bounded logcat export
kind: bug
status: done
area: diagnostics
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-12
updated: 2026-08-12
spec_reason: regression-tested-single-module
related_tasks: []
closed_at: "2026-08-11T21:09:18Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Architecture health, Cargo metadata, task contracts, and git diff checks passed.
---

## Goal

Keep both the beginning of a diagnostic run and the newest failure evidence when logcat exceeds the archive byte budget.

## Acceptance criteria

- A time-bounded logcat snapshot retains complete startup and newest log lines when input exceeds 512 KiB.
- The retained content remains valid UTF-8 and does not exceed the existing archive byte budget.
- Truncation remains explicit in archive completeness metadata.
- Focused and module tests plus repository static analysis pass.
