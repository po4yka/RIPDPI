---
id: RLY-1794335293698320
title: Fix initial relay race audit defects
kind: bug
status: doing
area: relay
priority: high
owner: Relay audit team
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-initial-relay-race-audit
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Resolve the initial relay race audit findings F1-F5. Reject duplicate candidates, select only live runtimes, require loser cleanup, preserve cold-start network changes, and align the documented product scope.

## Acceptance criteria

- Regression tests cover duplicate IDs, dead provisional candidates, cleanup failures, and cold-start handover.
- Existing relay and service lifecycle tests pass.
- Each confirmed finding has a separate commit.
- Current specifications describe the connected single-profile preflight.
- Required source gates and independent review pass.

## Ownership

- Relay worker: InitialRelayRaceRunner.kt, UpstreamRelaySupervisor.kt, AwgEgressReadinessVerifier.kt, and their focused tests. F1, F2, F4; no lifecycle files.
- Network worker: ServiceRuntimeStartStopOrchestrator.kt, NetworkHandoverProcessor.kt, NetworkHandoverMonitor.kt, and focused lifecycle tests. F3, plus the shared data NetworkHandoverMonitor interface and its generated API snapshot if needed; no relay runner files.
- Coordinator: OpenSpec, task records and generated board, F5 documentation, evidence, integration.
- No writer changes dependencies, schemas, locale sets, baselines, or golden fixtures.
- Each code writer uses a separate worktree; the coordinator serializes shared planning files.
