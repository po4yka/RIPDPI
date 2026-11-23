## Context

See proposal.md. Legacy booleans have no observation availability.

## Goals / Non-Goals

- Goal: Preserve unknown facts through display and export.
- Non-goal: Change JNI or native Boolean wire schemas.

## Decisions

- Use the Kotlin capability capture marker. Legacy Boolean values without that marker are unknown.
- Store explicit Private DNS modes in the existing field. Treat the legacy system placeholder as unknown. Preserve the strict mode when a legacy snapshot contains a resolver hostname.
- Omit native cellular metadata when roaming has no observation, because its existing wire field cannot represent unknown.

## Contracts and ownership

- metadata-worker owns DiagnosticsContextModels.kt, NetworkMetadataProvider.kt, NetworkSnapshotFactory.kt, DiagnosticsUiNetworkSnapshotMapper.kt and tests.
- Root owns export summaries, locale resources and integration; Home and classification writers own their files.
- No native wire, Cargo.lock, baseline, golden or locale changes.

## Risks / Trade-offs

- Legacy validation fields show unknown because old snapshots cannot distinguish missing capabilities.

## Migration Plan

- Older snapshots keep their stored data. Apply availability rules when building display and archive projections. Revert the commit to roll back.
- Gates: diagnostics, service and app unit tests; combined staticAnalysis and architecture health.
