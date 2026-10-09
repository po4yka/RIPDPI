---
id: DGN-1791521873290293
title: Record transfer progress and stopping points
kind: feature
status: review
area: diagnostics
priority: high
owner: transfer-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: transfer-progress-stop
created: 2026-10-09
updated: 2026-10-09
status_detail: Implementation and targeted native/Kotlin/UI checks passed; combined static gates running, CI and Android device evidence pending.
---

## Goal

Show live transfer progress and retain bounded body-byte timelines, timing and observed stop reasons for the existing throughput diagnostics.

## Acceptance criteria

- Live throughput progress shows body bytes and elapsed time before a probe finishes.
- Final results distinguish response completion, measurement-window limits, truncated framing, idle timeout, reset, cancellation and deadline.
- History and redacted exports preserve bounded numeric evidence; no body content is retained.
- Timing and counters are monotonic, partial data survives interruption, and old reports remain readable.
- Kotlin/native tests, static analysis, locale lint and architecture checks pass; remote CI and device limits are reported.

## Ownership

- Native writer owns native/rust source and tests, including optional progress wire fields. No dependencies, lockfiles, baselines or schema-version changes.
- UI writer owns app source, tests and all ten locale resource sets in an isolated worktree.
- Integration writer owns core/diagnostics models, codecs, validation, summary/export integration, docs and task/OpenSpec records.
- The integration writer serializes combined-tree checks and Git integration. Previous selective-matrix source remains separate until its authorized completion.
