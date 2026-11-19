---
id: DGN-1795014101783145
title: Isolate QUIC version diagnostics from residual flow state
kind: feature
status: backlog
area: diagnostics
priority: high
owner: diagnostic-evidence writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: quic-version-state-controls
created: 2026-11-18
updated: 2026-11-19
---

## Goal

Show whether QUIC Initial responses differ by version or by earlier packets on the same flow, using the existing ripdpi-packets builders.

## Acceptance criteria

- `REQ-QUIC-VERSION-STATE-CONTROLS-VERSION` in [the spec](../../../openspec/changes/quic-version-state-controls/specs/quic-version-state-controls/spec.md) passes its scenarios.
- `REQ-QUIC-VERSION-STATE-CONTROLS-ISOLATION` in [the spec](../../../openspec/changes/quic-version-state-controls/specs/quic-version-state-controls/spec.md) passes its scenarios.
- `REQ-QUIC-VERSION-STATE-CONTROLS-CONTROL` in [the spec](../../../openspec/changes/quic-version-state-controls/specs/quic-version-state-controls/spec.md) passes its scenarios.
- `REQ-QUIC-VERSION-STATE-CONTROLS-CLAIMS` in [the spec](../../../openspec/changes/quic-version-state-controls/specs/quic-version-state-controls/spec.md) passes its scenarios.

## Ownership

One writer owns the wire contract, `EngineContract.kt`, persistence, export, and locale changes for this task. Design and steps: [openspec/changes/quic-version-state-controls](../../../openspec/changes/quic-version-state-controls/design.md).

## Sources and scope

https://petsymposium.org/foci/2026/foci-2026-0010.php

The paper reports version-sensitive and residual flow filtering in March-April 2026 at three vantage points. Its observed timeout is a historical measurement, not a permanent provider guarantee. See also https://github.com/UPB-SysSec/RussiaQuicResults .

No implementation, device probe, credential use or deployment is performed by this planning change.
