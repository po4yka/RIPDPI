## Context

The report RLY-17 identifies four implementation defects and one specification mismatch. Current production Simple code uses one candidate. The core API accepts two. Target failure may intentionally stay inconclusive.

## Goals / Non-Goals

- Goal: fix F1-F4 with owning-module regression tests and reconcile F5.
- Goal: check runtime-generation evidence and candidate identity hypotheses without speculative protocol changes.
- Non-goal: restore a removed product race, add a winner cache, change protocols, use physical devices or real relay secrets.

## Decisions

- Validate profile identity before allocating jobs.
- Recheck provisional runtime lifetime at selection and refuse dead promotion.
- Propagate failed loser cleanup while preserving supervisor ownership and cancellation cleanup.
- Preserve the initial physical network baseline through startup. Use the existing handover path after connection, under its lifecycle mutex.
- Create a fresh initial-probe health engine for each runtime race, and reject resolved candidate kind/profile drift before allocating a runtime. Keep recent-success dominance for the same live runtime unchanged.
- Keep target-only failures inconclusive. Do not replace them with permanent relay failure.
- Update obsolete product promises to the current single-profile preflight. Preserve core race requirements.

## Contracts and ownership

- Modules: core/service and the data module that owns NetworkHandoverMonitor. Add a default cold-observation entry point to the shared monitor API. No Rust, JNI, protobuf, storage, locale, dependency, or wire schema change.
- Relay worker owns runner, supervisor, and focused relay tests.
- Network worker owns start/stop orchestration, handover monitor/processor, the shared monitor interface, its generated API snapshot if required, and focused lifecycle tests.
- Coordinator owns this change, task files, generated board, normative specification alignment, and combined validation.
- Shared planning files have one writer. Each code worker has a separate worktree.

## Risks / Trade-offs

- Cleanup failure now prevents selection. Tests must prove retry ownership and cancellation safety.
- A queued startup handover must not start before Connected or survive a stopped session.
- Physical network acceptance is separate from local coroutine evidence. No device/peer compatibility claim will be made.

## Migration Plan

No stored data migration. Revert each isolated fix if needed. Run focused service tests first, then core/service unit tests, staticAnalysis, ci-gates, architecture health, locked cargo metadata, and independent review before integration.
