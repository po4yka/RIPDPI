---
id: DGN-1795014015067016
title: Add bounded TLS interference hop comparisons
kind: feature
status: done
area: diagnostics
priority: medium
owner: diagnostic-evidence writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: tls-interference-hop-comparison
created: 2026-11-18
updated: 2026-11-20
closed_at: "2026-11-20T06:33:11Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "PR #743 merged as de007a006; CI run 44683066247 passed on the same tree; Pixel 8 Pro device run on 2c7a1c168 (see the archived verification.md)."
---

## Goal

Bound the hop distance of a middlebox that reacts to a test SNI with TTL-limited ClientHello attempts on non-root Android. The existing `platform_ttl` getter only reads the local outbound TTL and keeps that meaning.

## Acceptance criteria

- `REQ-TLS-INTERFERENCE-HOP-COMPARISON-CAPABILITY` in [the spec](../../../openspec/changes/tls-interference-hop-comparison/specs/tls-interference-hop-comparison/spec.md) passes its scenarios.
- `REQ-TLS-INTERFERENCE-HOP-COMPARISON-PAIR` in [the spec](../../../openspec/changes/tls-interference-hop-comparison/specs/tls-interference-hop-comparison/spec.md) passes its scenarios.
- `REQ-TLS-INTERFERENCE-HOP-COMPARISON-PRIVACY` in [the spec](../../../openspec/changes/tls-interference-hop-comparison/specs/tls-interference-hop-comparison/spec.md) passes its scenarios.
- `REQ-TLS-INTERFERENCE-HOP-COMPARISON-LIMITS` in [the spec](../../../openspec/changes/tls-interference-hop-comparison/specs/tls-interference-hop-comparison/spec.md) passes its scenarios.

## Ownership

One writer owns the wire contract, `EngineContract.kt`, persistence, export, and locale changes for this task. Design and steps: [openspec/changes/tls-interference-hop-comparison](../../../openspec/changes/tls-interference-hop-comparison/design.md).

## Sources and scope

https://ooni.org/post/2026-ooni-probe-tls-middlebox-experiment/

OONI describes paired control and test names with expiring requests. Its advanced path data can expose location; this proposal adopts local-first measurements instead of OONI publication.

No implementation, device probe, credential use or deployment is performed by this planning change.
