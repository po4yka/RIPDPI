---
id: RLY-1787332288948458
title: Record ripdpi-tls-spoof spike status and integration prerequisites
kind: chore
status: done
area: relay
priority: low
owner: unassigned
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-21
updated: 2026-08-21
spec_reason: docs-only
status_detail: Spike status recorded; validate green
closed_at: "2026-08-21T17:19:15Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Spike status, audit outcome, and promotion prerequisites recorded in docs/tasks/issues/tls-spoof-spike-status.md; taskctl validate green; board regenerated
---

## Goal

Make the spike status of the `ripdpi-tls-spoof` crate explicit in the portfolio so the crate is not mistaken for a shipped feature. The crate is a documented spike (see its lib.rs "Spike verdict"): the SNI-desync technique is validated at the byte level and unit-tested, but the crate has no workspace consumers — the signaling type has no transport, `RelaySpoofer` is not called from any relay upstream connector, and the demo binary is dead code.

## Acceptance criteria

- This issue records the spike status, the audit outcome, and the prerequisites for promoting the crate from spike to feature.
- Promotion work (transport for `SpoofRequest`, relay upstream-connector wiring, telemetry poller hookup) is either scheduled as follow-up tasks or explicitly declined with a rationale recorded here.
- Until promotion starts, no documentation outside the crate describes SNI spoofing as a working product capability.
