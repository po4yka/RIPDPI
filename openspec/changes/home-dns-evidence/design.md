## Context

Home compares public resolver answers. These can vary by network location.

## Goals / Non-Goals

- Goal: retain observations without unsupported cause attribution.
- Non-goal: change native DNS classification or wire schemas.

## Decisions

- Use existing UNKNOWN class for divergence. Preserve explicit native tampering controls.
- Use neutral summary text for legacy possible proxy and unavailable DoH results.

## Contracts and ownership

- Home evidence agent owns DefaultHomeAnalysisAugmentationSource, HomeOutcomeSynthesizer, BlockLayerDiagnosis and their tests. No shared serialized files.

## Risks / Trade-offs

- Some true interference remains inconclusive without positive controls.

## Migration Plan

No migration. Revert this commit to roll back. Run targeted JVM tests and staticAnalysis.
