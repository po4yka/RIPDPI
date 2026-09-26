# DGN-1790433094681403

## Objective

Keep scan admission reserved during DNS-corrected re-probe handoff.

## Ownership

This writer owns `core/diagnostics` scan execution coordination and tests. Other writers own export, `dpi`, `dpich`, and `rkn`. No serialized shared file is changed.

## Execution

## Verification

Run `./gradlew :core:diagnostics:testDebugUnitTest -Pripdpi.skipNativeBuild=true` and `./taskctl validate`.
- [x] DGN-1790433131392389 Reserve scan admission across DNS-corrected re-probe handoff #bug !high @item:DGN-1790433094681403
