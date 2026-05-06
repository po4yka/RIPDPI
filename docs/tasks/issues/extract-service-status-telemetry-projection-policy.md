---
title: Extract service status telemetry projection policy
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Extract service status telemetry projection policy #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

`ServiceStatusReporter` persists service status, emits failure events, snapshots
proxy/tunnel/relay/WARP telemetry, applies handover class, hashes the network
fingerprint, derives winning strategy families, and shapes
`RuntimeFieldTelemetry`. Split status persistence, telemetry export schema,
network identity, and strategy reporting policy.

## Audit citation

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceStatusReporter.kt` lines 30-184.

## Scope

- In scope: status persistence, failure-event emission, telemetry snapshotting,
  network fingerprint projection, strategy-family derivation, and runtime field
  telemetry shaping.
- Out of scope: changing stored status schema without a migration plan.

## Acceptance criteria

- [ ] Status reporter persists status and delegates projection policy to focused
    collaborators.
- [ ] Network fingerprint hashing and handover classification have an explicit
    owner.
- [ ] Winning-strategy family derivation is separated from status persistence.
- [ ] Unit tests cover projection behavior after extraction.

## Links

- [[Epic - Finish SRP residual architecture debt]]
