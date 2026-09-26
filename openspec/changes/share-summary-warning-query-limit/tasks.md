# DGN-1790431550325964: Preserve share summary warnings behind information events

## Objective

Select warning and error events before the summary limit.

## Ownership

Own only the share builder/new warning test and native-event read DAO, store contracts/adapters, fake, and Room test. Other agents own remaining diagnostics paths.

## Execution

## Verification

Run focused `:core:diagnostics` and `:core:diagnostics-data` unit tests with `-Pripdpi.skipNativeBuild=true`, strict OpenSpec validation, and `./taskctl validate`.
- [x] DGN-1790431615793563 Filter warning queries before limit and verify summaries #bug @item:DGN-1790431550325964
