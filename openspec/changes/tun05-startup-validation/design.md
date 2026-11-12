## Context
Task TRN-1794477195384429 fixes audit F1 and F3.

## Goals / Non-Goals
- Goal: carrier deadlines and shared URL validation.
- Non-goal: reconnect, DNS, wire format or persistence changes.

## Decisions
- Carrier enum supplies readiness timeout: UDP15s, WS40s (native15s+20s+5s margin).
- Use the existing OkHttp parser with WSS scheme adaptation. Editor and request share validation.

## Contracts and ownership
- Worker owns engine-api helper, supervisor awaitReady call only, runtime-state validator, editor and tests. Recovery worker owns other supervisor edits. Parent owns integration. No serialized shared files change.

## Risks / Trade-offs
- Parsing differs across languages; validate domain, IPv6, path and query boundaries.

## Migration Plan
No migration. Revert source commits to roll back. Run targeted engine readiness, runtime-state request and editor tests, then combined ci-gates.
