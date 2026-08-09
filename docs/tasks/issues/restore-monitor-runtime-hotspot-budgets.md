---
id: CIC-1786272446167159
title: Restore monitor runtime hotspot budgets on main
kind: bug
status: doing
area: ci
priority: critical
owner: CI and performance maintainer
parent: EPC-1786264762917503
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-09
updated: 2026-08-09
spec_reason: regression-tested-single-module
---

## Goal

Restore the architecture-health runtime-hotspot budget on `main` without weakening the baseline or exempting the affected monitor code.

## Acceptance criteria

- Reproduce the exact `architecture-health` failure from main CI run `31308145179`.
- Remove the new runtime hotspot indicators in the monitor path without extending any baseline.
- `python3 scripts/ci/check_architecture_health.py` reports zero new and zero worsened indicators on the rebased candidate.
- PR #373 is rebased/merged and the final `main` SHA passes the required architecture-health job.

## Work log

- Active implementation: PR #373, branch `codex/fix-main-runtime-hotspots-20260809`, candidate commit `0ffb31f10`.
- This backlog refresh tracks the existing work; it does not duplicate or merge that implementation branch.
