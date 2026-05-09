---
title: Split subprocess SOCKS relay manager
type: task
status: backlog
area: relay
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split subprocess SOCKS relay manager #repo/RIPDPI #area/relay #status/backlog 🔼

## Summary

Split `core/service/src/main/kotlin/com/poyka/ripdpi/services/SubprocessSocksRelayManager.kt` so subprocess lifecycle, asset extraction, readiness, managed client bridge, output parsing, and telemetry are not coupled.

## Context

The manager currently extracts relay binaries, builds and launches the process, probes version/readiness, reads process output, manages a client bridge, tracks errors, and projects telemetry from one module.

## Acceptance criteria

- [ ] Extract binary extraction and launch-plan construction.
- [ ] Extract process supervision and stop/cleanup handling.
- [ ] Extract readiness polling and version probing.
- [ ] Extract output event parsing.
- [ ] Extract telemetry projection and managed bridge orchestration.

## Completion outcome

Closing this task means subprocess relay startup has a launch-plan/extraction layer, a process supervisor, readiness/version probes, output parsing, managed bridge orchestration, and telemetry projection that can change independently.

## Regression guardrails

- Do not keep process launch, stdout parsing, readiness, and telemetry in one manager class.
- Do not parse process output inside lifecycle stop/start code.
- Do not let managed-client bridge behavior depend on binary extraction internals.
- Do not close the task without tests or fakes covering readiness and output parsing.
- Do not close the task without focused unit tests for each extracted launch, readiness, output, bridge, and telemetry slice where test seams are available.

## Links

- [[Epic - Post-refactor architecture cleanup]]
