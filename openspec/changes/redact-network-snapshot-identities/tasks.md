# DGN-1790433240059940: Exclude raw network identities from diagnostic snapshots

## Objective

Project capture-time network metadata to storage-safe values.

## Ownership

Own only `NetworkMetadataProvider.kt`, snapshot privacy tests, and this task/OpenSpec. Other agents own remaining diagnostics paths.

## Execution

## Verification

Run focused diagnostics unit tests, module detekt, strict OpenSpec validation, and `./taskctl validate`.
- [x] DGN-1790433363165985 Project network snapshot identities before persistence and verify serialization #bug !high @item:DGN-1790433240059940
