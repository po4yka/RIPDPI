---
id: SVC-1792856195708453
title: Use physical network authority for VPN handover and MTU
kind: bug
status: doing
area: service
priority: high
owner: Runtime integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: physical-network-handover-authority
created: 2026-10-24
updated: 2026-10-24
---

## Goal

Use one selected physical network snapshot for handover, remembered-network capture and tunnel MTU. A VPN overlay must not replace physical route authority.

## Acceptance criteria

- Stable selected physical evidence survives VPN overlay changes without a restart.
- True transport, DNS, private-DNS, route and MTU changes remain observable.
- Incomplete, blocked and ambiguous legacy observations stay unavailable.
- Scope hashing, serialized policy and binder-issued DNS authority remain unchanged.
- Full affected JVM, source, artifact and owned physical acceptance gates pass.

## Ownership

- Protocol worker owns the existing physical observer, fingerprint, handover and tunnel-parameter sources and their existing tests in its isolated worktree. Runtime-only model metadata is included only when needed for atomic handover capture.
- Coordinator owns this task, OpenSpec artifacts and integration. Security worker owns independent read-only review. Coordinator alone operates the physical device.
- Protocol ownership also includes the narrow cooldown identity consumer in NetworkHandoverProcessor.kt, HandoverAwareSession in ServiceRuntimeCoordinator.kt, ServiceRuntimeRegistry.kt and BaseServiceRuntimeCoordinatorTest.kt. Cooldown timing and persisted scope hashing stay unchanged.
- No other runtime, native, DNS lease, storage or provider file is in scope.
