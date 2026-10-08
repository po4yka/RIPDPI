## Context

See proposal.md. Current sequential platform reads can cross a network handover.

## Goals / Non-Goals

- Goal: Fail closed for mixed metadata.
- Non-goal: Bind public IP HTTP calls to a new socket interface or alter native wire types.

## Decisions

- Bracket capture with opaque physical epoch, default network, and authoritative underlay and VPN route observations.
- Discard all network-specific fields on mismatch or unavailable physical continuity.
- Read platform properties from the captured default Network.

## Contracts and ownership

- metadata-worker owns NetworkMetadataProvider.kt, NetworkSnapshotFactory.kt, their tests, and this task. Root owns locale resources and integration. Other writers own Home measurements and evidence classification.
- No Cargo.lock, wire, locale, golden, or baseline edits.

## Risks / Trade-offs

- Missing callback evidence produces unknown results. This avoids false attribution.

## Migration Plan

- No schema change in this slice. Old snapshots remain readable. Revert the commit to roll back.
- Gates: targeted diagnostics/service tests, combined staticAnalysis, architecture health.
