# DGN-1790432046570971

## Objective

Preserve manual scan options through hidden probe conflict resolution.

## Ownership

This writer owns `core/diagnostics` scan controller and tests. Other writers own export, `dpi`, `dpich`, and `rkn`. No serialized shared file is changed.

## Execution

## Verification

Run `./gradlew :core:diagnostics:testDebugUnitTest -Pripdpi.skipNativeBuild=true` and `./taskctl validate`.
- [x] DGN-1790432111994717 Preserve all manual scan options after hidden probe conflict resolution #bug !high @item:DGN-1790432046570971
