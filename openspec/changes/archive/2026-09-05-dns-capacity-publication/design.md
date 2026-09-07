## Context

Task DNS-1788602983485108 corrects a capacity-release race confirmed in CI and independent source review.

## Goals / Non-Goals

- Release lookup permits before a completed result can trigger another lookup.
- Preserve limits and cancellation; do not add retries or sleeps to hide the race.

## Decisions

Use explicit drop on the existing permit after result computation and before channel send. Channel synchronization then orders capacity release before result observation. Keep the existing atomic ordering.

## Contracts and ownership

The isolated DNS writer owns address.rs. Audit integration owns planning, the report and Git integration. Public contracts remain unchanged.

## Risks / Trade-offs

The permit must remain held while the resolver runs or hangs. Release only after success, error or caught panic produces a result.

## Migration Plan

No migration. Verify the complete transport crate, strict Clippy and required CI. Rollback is a normal source revert.
