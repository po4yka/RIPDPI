---
title: Epic - Remediate residual Compose P2 findings
type: epic
status: doing
area: epic
priority: high
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-14
updated: 2026-07-14
---

## Goal

Close every source-confirmed P2 Jetpack Compose finding from the July 2026 repeated Android audit without regressing the completed P1 remediation stack.

## Why now

The higher-priority Android and Compose findings are fixed on the parent branch. The remaining P2 debt still causes frame-rate recomposition, exposes unstable collection contracts in shared UI primitives, and can retain a temporary backup share file when its route leaves composition before an activity result is delivered.

## Key decisions

- Keep frame-rate animation state as `State` and read it from layout or draw modifiers.
- Use immutable collection types at public shared-component boundaries and propagate them through private helpers where needed.
- Give the in-flight backup-share file composition-scoped cleanup in addition to activity-result, failure, and stale-file cleanup.
- Treat the domain-editor composition write as already resolved by inherited P1 commit `0b4642716`; verify it, but do not create a duplicate no-op commit.

## Scope

- Defer `AnalysisProgressIndicator` pulse, shimmer, and progress reads out of composition.
- Defer `RipDpiConnectionActuator` carriage and stage-pulse reads out of composition.
- Replace ordinary `List` and `Set` parameters in public shared component APIs with persistent immutable collection contracts and update callers.
- Clean up a pending backup-share temp file when the controller leaves composition.
- Verify that `DomainBypassListScreen` hydrates and compiles editor state only from effects.

## Ship definition

- Each confirmed P2 finding is represented by its own atomic Conventional Commit.
- Focused tests cover phase-read structure, immutable component contracts, and backup-share disposal behavior.
- Compose compiler release reports, `staticAnalysis`, `testDebugUnitTest`, and architecture-health verification pass on the combined branch.

## Work log

- 2026-07-14: Created isolated `codex/fix-all-compose-p2` worktree on completed P1 commit `270187564` and source-verified four remaining implementation slices.
