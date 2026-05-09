---
title: Epic - Post-refactor architecture cleanup
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Epic - Post-refactor architecture cleanup #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Finish the remaining architecture cleanup surfaced by the 2026-05-09 re-audit after the SRP refactors. The static gates are green, but the audit still found residual coupling around the proxy runtime adapter, the proxy runtime state kernel, diagnostics lane composition, LOC baseline behavior, and Kotlin presentation/config hotspots.

## Current audit baseline

- `scripts/ci/check_architecture_health.py --check`: 57 current indicators, all baseline-covered; 0 new, 0 worsened, 0 stale.
- `scripts/ci/check_native_hotspot_budgets.py`: 0 over budget.
- `scripts/ci/check_file_loc_limits.py`: 0 new violations, 1 baseline exemption.
- `scripts/ci/check_native_architecture_contracts.py`: 0 violations.

## Scope

- Split the residual proxy-runtime adapter hub instead of letting it become the new integration god crate.
- Split `RuntimeState` by runtime concern so proxy execution no longer routes every operation through one state kernel.
- Keep diagnostics lane composition isolated behind explicit contracts or registration, not broad public dependency bundles.
- Tighten LOC baseline semantics where baseline-exempt files can silently grow.
- Reduce the remaining Kotlin baseline indicators in diagnostics, home, settings, and relay config support.

## Ship definition

- [ ] `ripdpi-proxy-runtime-adapter/src/model.rs` no longer contains multiple large inline public modules.
- [ ] `ripdpi-proxy-runtime/src/runtime/state.rs` is split into focused state/service modules with a small facade.
- [ ] Diagnostics lane composition has a bounded registration contract and no broad re-export seam for internal callers.
- [ ] LOC baseline exemptions cannot grow silently, or the remaining growth is explicitly blocked by a task-local budget.
- [ ] The highest-signal Kotlin P3 indicators from the audit are reduced or split into feature-owned surfaces.
- [ ] All architecture guardrails remain green with no baseline increases.

## Completion outcome

Closing this epic means the current post-refactor architecture debt is no longer merely baseline-covered. The remaining hotspots are either split into feature-owned/runtime-owned modules, protected by guardrails, or explicitly documented as intentional composition boundaries with narrow public APIs. Future re-audits should show fewer current P2/P3 indicators, no new dependency hubs, no silent LOC baseline growth, and no replacement god modules created by moving coupling from one file to another.

## Regression guardrails

- Do not replace old hotspots with new aggregate facades, broad `pub use` bundles, or "adapter" crates that expose every feature family.
- Do not increase architecture-health, LOC, or native-hotspot baselines as part of implementation unless a separate review explicitly accepts new debt.
- Do not allow one service/coordinator/DAO/mapper/composable to own unrelated runtime, policy, telemetry, UI, and persistence concerns.
- Do not leave compatibility layers as the primary API when typed, feature-owned contracts are available.
- Do not close child tasks until the matching static gates and targeted tests prove the split reduced the review surface.
- Do not close any refactoring task without adding focused unit tests for the refactored parts, or documenting why the slice is compile-time/static-analysis only and which existing tests cover it.

## Child tasks

- [[Split proxy runtime adapter model by operation family]]
- [[Decompose proxy runtime state kernel]]
- [[Isolate diagnostics runner lane composition]]
- [[Tighten LOC baseline growth and split desync TCP tests]]
- [[Reduce Kotlin baseline architecture indicators]]
- [[Split config relay support by feature]]
- [[Split settings JSON snapshot mappers by feature]]
- [[Split native proxy UI preference mappers]]
- [[Decompose detection runner orchestration]]
- [[Split indirect signs checker by signal family]]
- [[Split diagnostics database DAOs by domain]]
- [[Split VPN protect socket server responsibilities]]
- [[Split subprocess SOCKS relay manager]]
- [[Decompose runtime services state handle]]
- [[Tighten runtime decision ports exports]]
- [[Split relay core runtime composition]]
- [[Split strategy pack service workflows]]

## Verification

- [ ] `python3 scripts/ci/check_architecture_health.py --check`
- [ ] `python3 scripts/ci/check_native_hotspot_budgets.py`
- [ ] `python3 scripts/ci/check_file_loc_limits.py`
- [ ] `python3 scripts/ci/check_native_architecture_contracts.py`
- [ ] Targeted Kotlin or Rust tests for each touched module.
