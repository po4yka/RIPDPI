---
title: Split OwnedStackBrowserService into transport-layer components
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split OwnedStackBrowserService into transport-layer components #repo/RIPDPI #area/service #status/backlog ⏫

## Objective

Separate contracts, capability/ECH evidence, Android platform execution, native fallback execution, and response decoding so fetch-policy changes do not touch transport mechanics.

## Context

`OwnedStackBrowserService` defines request/response DTOs, backend selection, Android HttpEngine eligibility, ECH evidence, native owned-TLS fallback reasons, and page decoding in one service module. Later code also performs platform retry and native fallback orchestration.

Source: `core/service/src/main/kotlin/com/poyka/ripdpi/services/OwnedStackBrowserService.kt:33-117`

## Acceptance criteria

- [ ] Request/response DTOs extracted into a contracts module with no service dependencies.
- [ ] `EchCapabilityAdvisor` owns ECH evidence and Android HttpEngine eligibility checks.
- [ ] `AndroidPlatformFetcher` handles Android HttpEngine execution path.
- [ ] `NativeFallbackFetcher` handles owned-TLS native execution and fallback reason reporting.
- [ ] `ResponseDecoder` owns page decoding independently of transport.
- [ ] `OwnedStackBrowserService` becomes an orchestrator over the above components.
- [ ] No behavioral change verified by existing fetch integration tests.

## Definition of done

Each component compiles in isolation; integration tests green; fetch-policy changes touch only the contracts module.
