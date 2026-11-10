---
id: RLY-1794335295387247
title: Repair relay configuration contract audit findings
kind: bug
status: doing
area: relay
priority: high
owner: Relay contract repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-relay-config-contract
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Repair the RLY-19 relay configuration audit findings without adding relay modes or changing stored schemas.

## Acceptance criteria

- Explicit native activation selects the Native VPN provider in the same transaction.
- Trojan and AnyTLS imports reject invalid endpoints before durable mutation.
- Every JNI relay kind requires the current native schema before dispatch.
- Unknown Reality transports fail before listener readiness.
- Contract tests cover actual producers and consumers, defaults, and registry completeness.
- Resolve audit hypotheses with observed evidence. Fix confirmed defects; document intentional limits.
- Keep one implementation commit per confirmed problem and pass independent review, local gates, and source gates before main integration.

## Parallel ownership

- Kotlin worker: RelayProfileActivator and its callers/tests; NativeRelayProfileValidation and owning import/Room tests. Commit slices F1 and F2 stay separate.
- Rust worker: relay-android session config dispatch/tests, relay-core native schema constant export as needed, Reality transport dispatch/tests. Commit slices F3 and F4 stay separate.
- Hypothesis investigator: read-only H1 quality scope, H2 coherent profile reads, H3 DTO/reusable/drift tests; no writes until the coordinator assigns an isolated lane.
- Coordinator: this portfolio, OpenSpec planning/evidence, contract documentation and shared cross-language tests, integration and pushes. Each writer uses a separate worktree and does not revert another writer's changes.
- Serialized lanes: coordinator owns task records, board and architecture documents. No locale, lockfile, version catalog, protobuf, schema version, descriptor registry, baseline or golden changes are planned. Any needed shared lane is assigned before editing it.
