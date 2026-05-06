---
title: Epic - Clear post-SRP architecture regressions
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Epic - Clear post-SRP architecture regressions #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Close the architecture findings that remain after the residual SRP epic was
completed. The previous epic removed the major service/runtime hubs and made the
architecture-health gate stable again, but the latest audit still found one live
P2 indicator, two native hotspot-budget failures, one monitor-engine dependency
hub, several broad native root facades, two remaining proxy-runtime concrete
dependency edges, a long Android proxy config property strategy, two large
shared protocol/config modules, and one stale LOC-baseline hygiene problem.

This epic should finish the follow-up cleanup without widening baselines as a
substitute for refactoring.

## Why now

`scripts/ci/check_architecture_health.py --check` is stable with no new,
worsened, or stale architecture-health entries, but it still records one current
P2 baseline indicator. Separately, `check_native_hotspot_budgets.py` fails on
two native files, and `check_file_loc_limits.py` reports stale or missing
baseline entries after files were deleted or reduced. A full native Rust scan
also found remaining broad root facades, concrete proxy-runtime edges, and large
shared protocol/config modules that are not all covered by the hotspot budget.
These remaining problems are now small enough to track as a focused closure
epic.

## Scope

- In scope: the post-SRP re-audit findings and the additional native Rust
  findings captured in this epic's child tasks.
- In scope: module/API splits, import migration, guardrail/baseline cleanup, and
  targeted tests needed to prove the new shape.
- Out of scope: broad behavior changes, unrelated feature work, or increasing
  architecture/file-size baselines to hide current debt.

## Ship definition

- [ ] Proxy-mode service orchestration no longer appears as a P2 feature-spread
    indicator.
- [ ] TCP typed payload conversion/accessors are split below the native hotspot
    budget and remain covered by invariant tests.
- [ ] Monitor engine no longer depends directly on concrete diagnostics lanes
    that should be hidden behind runner/adapter contracts.
- [ ] Monitor engine root is a small facade and stays within the native hotspot
    budget.
- [ ] Remaining native broad-root facades are narrowed or moved behind explicit
    compatibility namespaces.
- [ ] Proxy runtime no longer depends directly on concrete failure-classifier or
    WS bootstrap crates where decision/adapter ports are sufficient.
- [ ] Large shared protocol/config modules found by the native audit are split
    by responsibility and covered by focused tests.
- [ ] File LOC baselines are refreshed only to remove deleted/reduced debt
    entries; no baseline is increased.
- [ ] These commands pass:
    `python3 scripts/ci/check_architecture_health.py --check`,
    `python3 scripts/ci/check_native_hotspot_budgets.py`,
    `python3 scripts/ci/check_file_loc_limits.py`,
    and `python3 scripts/ci/check_native_architecture_contracts.py`.

## Child tasks

- [[Finish proxy coordinator runtime-family split]]
- [[Split TCP typed payload adapter by family]]
- [[Decouple monitor engine from concrete diagnostics lanes]]
- [[Narrow monitor engine public facade]]
- [[Clean stale architecture LOC baselines]]
- [[Finish native diagnostics root facade cleanup]]
- [[Narrow failure classifier public root]]
- [[Narrow privileged ops public root]]
- [[Narrow tunnel core public root]]
- [[Remove proxy runtime concrete classifier and WS bootstrap edges]]
- [[Split Android proxy adapter config property strategy]]
- [[Decompose SOCKS5 core server module]]
- [[Split relay core config by backend]]

## Links

- [[Epic - Finish SRP residual architecture debt]]
- Child issues: 13
