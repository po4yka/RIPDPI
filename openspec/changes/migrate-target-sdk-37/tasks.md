# AND-1787932839013427: Migrate Android runtime behavior to target SDK 37

## Objective

Deliver the approved runtime migration with functional permission recovery and observed tests.

## Ownership

Codex owns implementation and serialized files; isolated TLS-test author owns only
OwnedStackBrowserServiceTest.kt, AndroidLocalNetworkAccessTest.kt,
UnresolvedHostnameNetworkShadow.kt, MainViewModelTest.kt, LocalNetworkRuntimeTest.kt
and DiagnosticsLocalNetworkPreflightTest.kt until their test diffs are imported.

## Execution

- [x] AND-1787933125533035 Implement target SDK 37 migration with functional LAN access, TLS enforcement and runtime acceptance #feature !high @item:AND-1787932839013427
- [x] AND-1787933125693547 Implement demand-driven LAN permission orchestration and boundary tests in app, service and diagnostics #feature !high @item:AND-1787932839013427
- [x] AND-1787933125835921 Preserve certificate failures across HTTP fallback and update NSC platform serialization #feature !high @item:AND-1787932839013427
- [ ] AND-1787933125977204 Verify target SDK 37, Robolectric, and the hosted Android API matrix #feature !high @item:AND-1787932839013427
- [ ] AND-1787933126138160 Verify application packages, native artifacts, and the API 37 emulator smoke #feature !high @item:AND-1787932839013427

## Verification

Run targeted Gradle tests, static analysis, locale lint, and the hosted Android matrix.
Record APK, native ELF, and API 37 emulator results. Physical and same-L2 LAN tests
are outside the current app acceptance scope.
