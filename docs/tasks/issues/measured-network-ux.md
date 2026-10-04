---
id: EPC-1791124000119505
title: Implement measured network UX and durable connection controls
kind: epic
status: doing
area: epic
priority: high
owner: Codex sequential delivery
parent: null
blocked_by: []
spec_mode: required
openspec_change: measured-network-ux
created: 2026-10-04
updated: 2026-10-04
---

## Goal

Implement the seven approved Mobbin research improvements as sequential, runnable Android feature slices, each reviewed, validated, committed, integrated into main, and pushed.

## Acceptance criteria

- Explain direct and active-path scan scope and lifecycle consequences before running and in results.
- Explain measured metrics with units, aggregation/window and freshness; preserve no-data and uncertainty.
- Show factual active versus saved configuration and actionable recovery/permission states.
- Search and group actual diagnostic and saved relay profiles without losing selection.
- Preview diagnostic exports, apply supported redaction, and handle cancel/error without sharing.
- Persist timed pause and resume intent with explicit cancellation, restart recovery and truthful countdown.
- Persist favorite/recent profiles and expose measured automatic selection using actual payload URL-tests.
- Keep every new resource in all ten locales; use existing RDS components.
- Observe targeted tests, lint, visual artifacts, final combined gates, remote SHA and terminal CI; report device gaps honestly.
- Never close unfinished behavior or refusal-only output as delivered.

## Ownership and serialized lanes

- Root owns portfolio/OpenSpec state, integration to main, push, and final evidence.
- One implementation sub-agent at a time owns the active feature slice in its own worktree; root never edits its production files concurrently.
- Locale sets, persistence schemas, and golden fixtures have exactly one writer at a time.
- Independent review agents are read-only. Heavy commands use build-gate and four workers/jobs maximum.
- Research source: docs/design/mobbin-network-ux-research-2026-10-04.md.
