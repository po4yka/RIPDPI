---
id: SVC-1792856195708453
title: Use physical network authority for VPN handover and MTU
kind: bug
status: done
area: service
priority: high
owner: Runtime integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: physical-network-handover-authority
created: 2026-10-24
updated: 2026-10-31
status_detail: Implementation, independent review, 6288 JVM tests and all four physical USB scenarios passed; final hosted acceptance remains open.
closed_at: "2026-10-31T06:31:52Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Landed in b3bdf4263 (physical candidate 47a5ee708 passed all four owned USB scenarios); full push CI run 40122030612 on b3bdf4263 passed ci-required with 48 jobs; main 32a9b2c8a passed run 41170842447.
---

## Goal

Use one selected physical network snapshot for handover, remembered-network capture and tunnel MTU. A VPN overlay must not replace physical route authority.

## Acceptance criteria

- Stable selected physical evidence survives VPN overlay changes without a restart.
- True transport, DNS, private-DNS, route and MTU changes remain observable.
- Incomplete, blocked and ambiguous legacy observations stay unavailable.
- Scope hashing, serialized policy and binder-issued DNS authority remain unchanged.
- Full affected JVM, source, artifact and owned physical acceptance gates pass.

## Ownership

- Protocol worker owns the existing physical observer, fingerprint, handover and tunnel-parameter sources and their existing tests in its isolated worktree. Runtime-only model metadata is included only when needed for atomic handover capture.
- Coordinator owns this task, OpenSpec artifacts and integration. Security worker owns independent read-only review. Coordinator alone operates the physical device.
- Protocol ownership also includes the narrow cooldown identity consumer in NetworkHandoverProcessor.kt, HandoverAwareSession in ServiceRuntimeCoordinator.kt, ServiceRuntimeRegistry.kt and BaseServiceRuntimeCoordinatorTest.kt. Cooldown timing and persisted scope hashing stay unchanged.
- Rename the now-general CandidateRelayNetworkEpoch owner and file to PhysicalNetworkObserver with no alias. Protocol ownership includes rename-only call sites in AndroidRuntimeEvidenceModule.kt and ActiveAwgMigrationProbe.kt, plus existing owned consumers and service documentation.
- No other runtime, native, DNS lease, storage or provider file is in scope.

Protocol worker also owns import, type and constructor rename-only edits in:

- app/src/main/kotlin/com/poyka/ripdpi/subscription/SelectorProbeScope.kt
- app/src/main/kotlin/com/poyka/ripdpi/subscription/SelectorUrltestModule.kt
- app/src/main/kotlin/com/poyka/ripdpi/subscription/DeviceMigrationCoordinator.kt
- app/src/main/kotlin/com/poyka/ripdpi/ui/screens/profiles/ProfileUtilityMeasurementCoordinator.kt
- app/src/test/kotlin/com/poyka/ripdpi/ui/screens/profiles/ProfileUtilityViewModelTest.kt
- app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/XrayProviderE2ETest.kt
- app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/MigrationTransportE2ETest.kt
- app/src/androidTest/kotlin/com/poyka/ripdpi/integration/ProfileQualityNativeTest.kt
- app/src/androidTest/kotlin/com/poyka/ripdpi/integration/SelectorRuntimeInstrumentedTest.kt

These call sites keep their behavior and assertions unchanged. The complete
app JVM suite and instrumentation compilation validate the renamed API.
