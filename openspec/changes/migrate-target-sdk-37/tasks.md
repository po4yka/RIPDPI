# AND-1787932839013427: Migrate Android runtime behavior to target SDK 37

## Objective

Deliver the approved runtime migration with functional permission recovery and observed tests.

## Ownership

Codex owns implementation and serialized files; isolated TLS-test author owns only
OwnedStackBrowserServiceTest.kt, AndroidLocalNetworkAccessTest.kt,
UnresolvedHostnameNetworkShadow.kt, MainViewModelTest.kt, LocalNetworkRuntimeTest.kt
and DiagnosticsLocalNetworkPreflightTest.kt until their test diffs are imported.

## Execution

- [ ] AND-1787933125533035 Implement target SDK 37 migration with functional LAN access, TLS enforcement and runtime acceptance #feature !high @item:AND-1787932839013427
- [x] AND-1787933125693547 Implement demand-driven LAN permission orchestration and boundary tests in app, service and diagnostics #feature !high @item:AND-1787932839013427
- [x] AND-1787933125835921 Preserve certificate failures across HTTP fallback and update NSC platform serialization #feature !high @item:AND-1787932839013427
- [ ] AND-1787933125977204 Update target SDK, Robolectric, managed device matrix and mandatory API 37 LAN smoke #feature !high @item:AND-1787932839013427
- [ ] AND-1787933126138160 Verify all variant builds, native artifacts, lifecycle UI export and physical Android 37 acceptance #feature !high @item:AND-1787932839013427

## Verification

Targeted and full Gradle tests/staticAnalysis, locale lint, all-variant debug/release,
native --locked checks, ELF/16-KB runtime, API 27/33/35/36/37 matrix, and physical API 37 LAN.
