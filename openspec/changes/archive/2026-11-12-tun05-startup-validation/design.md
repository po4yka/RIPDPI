## Context
Task TRN-1794477195384429 fixes audit F1 and F3.

## Goals / Non-Goals
- Goal: carrier deadlines and shared URL validation.
- Non-goal: reconnect implementation, wire format or persistence changes. The outer reload budget includes the separately owned underlay bootstrap.

## Decisions
- Carrier enum supplies readiness timeout: UDP15s, WS40s (native15s+20s+5s margin).
- Use the existing OkHttp parser with WSS scheme adaptation. Editor and request share validation.

## Contracts and ownership
- Worker owns engine-api helper, supervisor awaitReady call only, runtime-state validator, editor and tests. Recovery worker owns other supervisor edits. Parent owns integration. No serialized shared files change.

## Risks / Trade-offs
- Parsing differs across languages; validate domain, IPv6, path and query boundaries.

## Migration Plan
No migration. Revert source commits to roll back. Run targeted engine readiness, runtime-state request and editor tests, then combined ci-gates.

- Integration decision: AWG WS reload has a 60-second bound; other carriers keep 45 seconds. Parent owns ServiceRuntimeCoordinator and its existing behavior tests.
