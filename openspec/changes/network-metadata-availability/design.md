## Context

See proposal.md. Legacy booleans have no observation availability.

## Goals / Non-Goals

- Goal: Preserve unknown facts through display and export.
- Non-goal: Change JNI or native Boolean wire schemas.

## Decisions

- Add optional Kotlin availability metadata; absent legacy metadata is unknown for Boolean observations.
- Preserve explicit legacy Private DNS hostnames but do not infer system mode from a default string.
- Omit native cellular metadata when roaming has no observation, because its existing wire field cannot represent unknown.

## Contracts and ownership

- metadata-worker owns DiagnosticsContextModels.kt, NetworkMetadataProvider.kt, NetworkSnapshotFactory.kt, DiagnosticsUiNetworkSnapshotMapper.kt and tests.
- Root owns export summaries, locale resources and integration; Home and classification writers own their files.
- No native wire, Cargo.lock, baseline, golden or locale changes.

## Risks / Trade-offs

- Legacy validation fields show unknown because old snapshots cannot distinguish missing capabilities.

## Migration Plan

- Add an optional archive field. Older archives decode without it. No data rewrite. Revert the commit to roll back.
- Gates: diagnostics, service and app unit tests; combined staticAnalysis and architecture health.
