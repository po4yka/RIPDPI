# DGN-1790430631972892

## Objective

Publish in-path completion only after finalization writes succeed, with report evidence on failure.

## Ownership

This writer owns `core/diagnostics` finalization, scan execution coordination, and their tests. Other writers own export, `dpi`, `dpich`, and `rkn`. No serialized shared file is changed.

## Execution

- [x] DGN-1790430759017178 Keep in-path report staged until finalization succeeds and preserve failure evidence #bug !high @item:DGN-1790430631972892

## Verification

Run `./gradlew :core:diagnostics:testDebugUnitTest -Pripdpi.skipNativeBuild=true` and `./taskctl validate`.
