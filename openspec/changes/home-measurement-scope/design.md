## Context

Native stages have a network scope guard. Home augmentation runs later and needs the same boundary.

## Goals / Non-Goals

- Goal: retain the admission scope through finalization and reject stale network authority.
- Non-goal: change native protocols, serialized schemas, or network binding.

## Decisions

- Reuse DiagnosticsNetworkScope, which checks both physical epoch and fingerprint and latches invalidation.
- Keep the scope in memory in the run closure; never serialize it.
- Validate before and after augmentation and use a conservative final result on invalidation.

## Contracts and ownership

- The home-scope writer owns HomeCompositeOutcomeFinalizer, DefaultDiagnosticsHomeCompositeRunService, DefaultHomeAnalysisAugmentationSource and their tests.
- No Rust, wire, storage, locale, golden, or dependency changes. Integration owns the generated board.

## Risks / Trade-offs

- Missing callback evidence removes network conclusions. Stable-scope tests preserve normal behavior.
- Cancellation must retain coroutine semantics and must not leak scope entries.

## Migration Plan

No migration. Revert the scoped source change to roll back. Run diagnostics and app unit tests, staticAnalysis, and architecture checks on the combined tree.
