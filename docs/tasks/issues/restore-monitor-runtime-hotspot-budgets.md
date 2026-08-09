---
id: CIC-1786272446167159
title: Restore monitor-engine native hotspot budgets on main
kind: bug
status: todo
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
status_detail: "PR #373 fixed the earlier runtime hotspot regression at 83197c824. Diagnostics attempt export 64a7f1c7c then introduced nine monitor-engine native hotspot overages; main CI run 31310877833 reproduced the new failure."
---

## Goal

Restore the monitor-engine native production-LoC budgets on `main` without weakening the baseline or exempting the affected code.

## Acceptance criteria

- Reproduce the nine `check_native_hotspot_budgets.py` overages from main CI run `31310877833` at `40742a63e`.
- Bring the affected monitor-engine files back within their production-LoC budgets without extending `config/static/native-hotspot-production-loc.json`.
- Both `python3 scripts/ci/check_native_hotspot_budgets.py` and `python3 scripts/ci/check_architecture_health.py` pass on the rebased candidate.
- The exact final `main` SHA passes the required `architecture-health` job.

## Work log

- PR #373 landed as `83197c824` and fixed the earlier runtime plan/stage hotspot regression.
- The later diagnostics attempt-export commit `64a7f1c7c` introduced nine new monitor-engine overages; no active repair branch exists.
