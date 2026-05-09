---
title: Split indirect signs checker by signal family
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split indirect signs checker by signal family #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

Split `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/IndirectSignsChecker.kt` by signal source so VPN/interface, DNS, dumpsys, routing, MTU, and evidence shaping logic evolve independently.

## Context

The checker currently owns signal catalogs, OS interface inspection, network capability checks, routing and MTU probes, DNS resolver classification, dumpsys parsing, and evidence assembly in one large checker.

## Acceptance criteria

- [ ] Extract network capability and interface signal probes.
- [ ] Extract DNS resolver signal classification.
- [ ] Extract dumpsys parsing and service-state signals.
- [ ] Extract evidence/detail assembly into a small builder.
- [ ] Keep the checker-facing API stable and preserve current classifications.

## Completion outcome

Closing this task means indirect-sign detection is assembled from signal-family probes with one small checker coordinator. Interface, capability, DNS, routing, MTU, and dumpsys changes should be isolated.

## Regression guardrails

- Do not put all signal constants, OS probes, dumpsys parsers, and evidence formatting into one replacement object.
- Do not let DNS resolver catalog changes touch network-interface or VPN-service probes.
- Do not mix parsing raw OS output with user-facing evidence text in the same module.
- Do not close the task unless existing classifications are preserved by tests or golden cases.
- Do not close the task without focused unit tests for each extracted signal-family parser/probe.

## Links

- [[Epic - Post-refactor architecture cleanup]]
