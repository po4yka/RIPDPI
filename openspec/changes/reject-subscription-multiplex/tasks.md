# DAT-1791477482125477

## Objective

Preserve subscription protocol intent during import.

## Ownership

Mux import writer owns SingBoxSubscriptionParser.kt, SingBoxMultiplexImportTest.kt, VlessRealityImportTest.kt, and this change. No serialized shared-file lane is needed. The parent integration writer regenerates the combined task board.

## Execution

- [x] DAT-1791477920421310 Fix multiplex rejection and no-flow mapping with parser regression tests #bug !high @item:DAT-1791477482125477
