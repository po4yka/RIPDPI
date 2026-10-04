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
- Root owns only the Xray acceptance fixture, instrumentation evidence and CI failure capture in the isolated network-ux-xray-evidence worktree while the implementation agent owns the configuration slice. This lane gates real provider acceptance without changing routing or test assertions.
- Research source: docs/design/mobbin-network-ux-research-2026-10-04.md.

## Acceptance incident

- 2026-10-04: Reopened scope step EPC-1791124243600668 after real API 37 device validation. Scope controls and explanations passed, but an active-VPN scan failed before session persistence with a generic start error. Local UI tests had not exercised the public error projection for unavailable route evidence. The recovery slice must preserve typed unavailable reasons and observe a real scan retry without weakening authenticated route eligibility. Positive active-path acceptance remains unverified.
- 2026-10-04: Reopened metrics step EPC-1791124244103077 after real API 37 Home inspection showed nominal quality and zero RTT/jitter with zero samples. Health/detail fixtures and local tests did not exercise this Home projection. The correction must distinguish absent and partial measurements on Home and observe the real no-sample state.
