---
title: Split DefaultConnectionPolicyResolver into separate policy services
type: task
status: backlog
area: routing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split DefaultConnectionPolicyResolver into separate policy services #repo/RIPDPI #area/routing #status/backlog ⏫

## Objective

Extract DNS selection, runtime-context assembly, remembered-policy matching, and signature/report construction out of `DefaultConnectionPolicyResolver.resolve` into dedicated policy services.

## Context

`DefaultConnectionPolicyResolver.resolve` pulls settings, fingerprinting, DNS path preference, direct-path capabilities, preferred edges, runtime context, baseline policy, remembered policy matching, and signature construction into one decision path. This couples DNS policy, route memory, runtime context shaping, and startup fallback behavior.

Source: `core/service/src/main/kotlin/com/poyka/ripdpi/services/ConnectionPolicyResolver.kt:81-130`

## Acceptance criteria

- [ ] `DnsSelectionService` encapsulates DNS path preference logic.
- [ ] `RuntimeContextAssembler` builds the runtime context object independently.
- [ ] `RememberedPolicyMatcher` owns remembered-policy lookup and matching.
- [ ] `PolicySignatureBuilder` constructs signatures and reports.
- [ ] `DefaultConnectionPolicyResolver.resolve` delegates to all four; its own line count drops meaningfully.
- [ ] Existing routing unit tests pass; add tests for each extracted service.

## Definition of done

Each service has its own test class; resolver compiles and passes all existing tests.
