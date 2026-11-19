# DAT-1795085420746769: Consume ripdpi-bundle v3 and feed endpoint health into failover

## Objective

The client parses bundle v3, rejects v2, and orders failover candidates by endpoint health, proven by golden tests.

## Ownership

core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/SingBoxSubscriptionParser.kt and PayloadFailoverImport.kt, core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/PayloadFailoverPolicy.kt, core/data/src/test/kotlin/com/poyka/ripdpi/data/RipdpiBundleContractTest.kt, core/data/src/test/resources/contract/, docs/server-integration.md. Serialized lane: vendored contract files and schema-version constants (after R2).

## Execution

- [ ] DAT-1795085444740643 Re-vendor the v3 bundle schema and golden fixtures (valid, v2, expired, down) with the drift pin in RipdpiBundleContractTest.kt #feature !high @item:DAT-1795085420746769
- [ ] DAT-1795085445713982 Bump RIPDPI_SCHEMA_VERSION to 3 in SingBoxSubscriptionParser.kt, parse health and field_report, reject v2 with a typed error #feature !high @item:DAT-1795085420746769
- [ ] DAT-1795085446935178 Implement the pure health evaluation with an injected clock and expose server_ref and field_report in the parse result #feature !high @item:DAT-1795085420746769
- [ ] DAT-1795085447701237 Feed health ordering into PayloadFailoverImport.kt and PayloadFailoverPolicy.kt candidate selection #feature !high @item:DAT-1795085420746769
- [ ] DAT-1795085448528033 Add golden tests for ordering and expiry under core/data and core/data/runtime-state, and update docs/server-integration.md #feature !high @item:DAT-1795085420746769

## Verification

./gradlew :core:data:testDebugUnitTest; just ci-gates; ./taskctl validate. Live acceptance against a real v3 bundle needs deploy D4.
