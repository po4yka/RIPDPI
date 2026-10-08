# DGN-1791484460786370: Keep Home measurements within the original network scope

## Objective

Reject late measurements after network scope changes.

## Ownership

HomeCompositeOutcomeFinalizer, DefaultDiagnosticsHomeCompositeRunService, DefaultHomeAnalysisAugmentationSource and related tests. No serialized shared-file changes.

## Execution

## Verification

Diagnostics and app unit tests; staticAnalysis; architecture checks. Integration runs Gradle in its cached worktree.
- [x] DGN-1791484541893864 Guard Home network augmentation and unknown capabilities with regression coverage #bug @item:DGN-1791484460786370
