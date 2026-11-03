---
id: DGN-1790430571977609
title: Preserve cancellation in RKN diagnostics
kind: bug
status: done
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-26
updated: 2026-11-04
spec_reason: regression-tested-single-module
closed_at: "2026-11-03T20:00:29Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Observed at 9b2a2f478: rkn/ sources rethrow CancellationException (RknLayeredProbePipeline, SelfInfoFetcher, SystemDohDnsComparator); gradle :core:diagnostics:testDebugUnitTest --tests 'com.poyka.ripdpi.diagnostics.rkn.*' passed (exit 0)"
---

## Goal

Canceling an RKN diagnosis stops DNS, TCP, TLS, and HTTP probes without reporting a network failure.

## Acceptance criteria

- A canceled probe propagates `CancellationException` to the caller.
- A parent timeout stops the DNS comparison; an individual lookup timeout remains an inconclusive lookup.
- The focused RKN unit tests pass.

## Parallel ownership

- This worktree owns `core/diagnostics/.../rkn/` and its unit tests.
- Other worktrees own `core/diagnostics/.../export/`, `finalization/` and scan execution, and `dpi/` plus `dpich/` probes. Do not modify those paths here.
