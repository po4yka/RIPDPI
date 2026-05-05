---
title: Split UpstreamRelaySupervisorSupport into merge, validation, and resolution modules
type: task
status: backlog
area: relay
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split UpstreamRelaySupervisorSupport into merge, validation, and resolution modules #repo/RIPDPI #area/relay #status/backlog 🔼

## Objective

Separate profile merge/defaulting, backend validators, chain relay resolution, finalmask validation, and credential resolution so adding or changing a relay backend touches only the relevant module.

## Context

`UpstreamRelaySupervisorSupport.kt` merges profile config, validates per-backend feature gates, resolves chain relay hops, and handles credentials/profile store lookup in one support file. Adding or changing a relay backend still touches shared merge and validation code.

Source: `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisorSupport.kt`

## Acceptance criteria

- [ ] `RelayProfileMerger` owns profile config merge and defaulting logic.
- [ ] `RelayBackendValidator` owns per-backend feature gate validation.
- [ ] `ChainRelayResolver` owns chain relay hop resolution.
- [ ] `FinalmaskValidator` owns finalmask validation independently.
- [ ] `RelayCredentialResolver` owns credential and profile store lookup.
- [ ] `UpstreamRelaySupervisorSupport` delegates to the above; no logic duplication.
- [ ] Existing relay supervisor tests pass; new unit tests for each module.

## Definition of done

Each module has its own test file; supervisor support compiles; relay integration tests green.
