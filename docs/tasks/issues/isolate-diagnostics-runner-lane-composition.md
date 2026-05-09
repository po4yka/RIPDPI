---
title: Isolate diagnostics runner lane composition
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

- [ ] #task Isolate diagnostics runner lane composition #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

Constrain diagnostics lane composition so `ripdpi-diagnostics-runner` remains an execution seam instead of becoming the permanent concrete-lane dependency hub.

## Context

The runner still directly depends on candidates, classification, DNS, fat-header, HTTP, Telegram, TLS, transport, DNS resolver, failure classifier, packets, and proxy config. The current state is acceptable as a composition point, but new probe families should register through contracts rather than adding more direct fanout.

## Acceptance criteria

- [ ] Define a small lane registration or runner adapter contract.
- [ ] Move concrete lane wiring behind per-lane modules or adapter structs.
- [ ] Prevent internal callers from depending on broad lane bundles.
- [ ] Keep `ripdpi-monitor-engine` free of concrete diagnostics lane dependencies.
- [ ] Existing diagnostics runner and monitor-engine tests remain green.

## Completion outcome

Closing this task means diagnostics lane composition is explicit and bounded. New DNS/HTTP/TLS/Telegram/transport lanes should register through lane contracts or per-lane adapters instead of expanding one concrete dependency hub.

## Regression guardrails

- Do not expose a broad diagnostics facade from runner, monitor, or adapter roots for internal use.
- Do not add concrete probe-lane dependencies back to `ripdpi-monitor-engine`.
- Do not make lane registration require editing a central module with every probe family's implementation details.
- Do not close the task if adding one probe family still forces changes across unrelated lane bindings.
- Do not close the task without focused unit tests for lane registration/adapters, or a written explanation of why a lane slice is compile-time/static-analysis only.

## Links

- [[Epic - Post-refactor architecture cleanup]]
