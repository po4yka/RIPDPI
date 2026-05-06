---
title: Clean stale architecture LOC baselines
type: task
status: backlog
area: ci
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Clean stale architecture LOC baselines #repo/RIPDPI #area/ci #status/backlog 🔼

## Summary

`check_file_loc_limits.py` now reports stale baseline data after the SRP cleanup:
two diagnostics support files no longer exist, and `AdvancedSettingsBinder` is
far below its old baseline. The checker passes, but the stale entries weaken the
guardrail signal and should be removed after confirming no debt is being
accepted.

## Audit citation

- `config/static/file-loc-baseline.json` lines 4-19.
- Checker output: stale baseline for
  `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsBinder.kt`;
  missing baseline paths for deleted diagnostics support files.

## Scope

- In scope: remove deleted/reduced LOC baseline entries and rerun file-size
  guardrails.
- Out of scope: increasing any LOC baseline or accepting new oversized files.

## Acceptance criteria

- [ ] Deleted diagnostics support file entries are removed from
    `config/static/file-loc-baseline.json`.
- [ ] Reduced `AdvancedSettingsBinder` no longer has a stale oversized baseline.
- [ ] No baseline value is increased.
- [ ] `python3 scripts/ci/check_file_loc_limits.py` passes without stale or
    missing baseline entries.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
