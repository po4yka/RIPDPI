---
id: DGN-1795014097920678
title: Compare selective availability across explicit network sessions
kind: feature
status: review
area: diagnostics
priority: medium
owner: diagnostic-evidence writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: paired-network-availability
created: 2026-11-18
updated: 2026-11-20
---

## Goal

Compare two explicit selective-matrix sessions from different networks to separate path-specific failures from unavailable endpoints.

## Acceptance criteria

- `REQ-PAIRED-NETWORK-AVAILABILITY-PAIR` in [the spec](../../../openspec/changes/paired-network-availability/specs/paired-network-availability/spec.md) passes its scenarios.
- `REQ-PAIRED-NETWORK-AVAILABILITY-CONTROL` in [the spec](../../../openspec/changes/paired-network-availability/specs/paired-network-availability/spec.md) passes its scenarios.
- `REQ-PAIRED-NETWORK-AVAILABILITY-SCOPE` in [the spec](../../../openspec/changes/paired-network-availability/specs/paired-network-availability/spec.md) passes its scenarios.
- `REQ-PAIRED-NETWORK-AVAILABILITY-EVIDENCE` in [the spec](../../../openspec/changes/paired-network-availability/specs/paired-network-availability/spec.md) passes its scenarios.

## Ownership

One writer owns the wire contract, `EngineContract.kt`, persistence, export, and locale changes for this task. Design and steps: [openspec/changes/paired-network-availability](../../../openspec/changes/paired-network-availability/design.md).

## Sources and scope

https://github.com/vadim-tolmachev/allowlist-probe

The pilot motivates same-device near-time controls and distinguishing a dead endpoint from path-specific failure. Its small sample is not country-wide proof; neither public endpoint lists nor claimed permitted ASNs are trusted inputs.

No implementation, device probe, credential use or deployment is performed by this planning change.
