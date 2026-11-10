---
id: RLY-1794335295387247
title: Repair relay configuration contract audit findings
kind: bug
status: doing
area: relay
priority: high
owner: Relay contract repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-relay-config-contract
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Repair the RLY-19 relay configuration audit findings without adding relay modes or changing stored schemas.

## Acceptance criteria

- Explicit native activation selects the Native VPN provider in the same transaction.
- Trojan and AnyTLS imports reject invalid endpoints before durable mutation.
- Every JNI relay kind requires the current native schema before dispatch.
- Unknown Reality transports fail before listener readiness.
- Contract tests cover actual producers and consumers, defaults, and registry completeness.
- Resolve audit hypotheses with observed evidence. Fix confirmed defects; document intentional limits.
- Keep one implementation commit per confirmed problem and pass independent review, local gates, and source gates before main integration.

## Parallel ownership

- Kotlin worker: RelayProfileActivator and its callers/tests; NativeRelayProfileValidation and owning import/Room tests. Commit slices F1 and F2 stay separate.
- Rust worker: relay-android session config dispatch/tests, relay-core native schema constant export as needed, Reality transport dispatch/tests. Commit slices F3 and F4 stay separate.
- Coherent-read worker: core/service RelayRuntimeProfileReader and its regression tests, plus RelayKindDescriptorDriftTest completeness checks, in rly19-coherent. These are separate fix/test commits.
- Rust worker also owns runtime_validation planned reusable corrections and owning backend oracle tests as a separate slice.
- The read-only hypothesis investigation is complete. H1 remains an intentional process aggregate; coordinator owns its scope documentation and unsupported chain-mux documentation.
- Coordinator: this portfolio, OpenSpec planning/evidence, contract documentation and shared cross-language tests, integration and pushes. Each writer uses a separate worktree and does not revert another writer's changes.
- Serialized lanes: coordinator owns task records, board and architecture documents. No locale, lockfile, version catalog, protobuf, schema version, descriptor registry, baseline or golden changes are planned. Any needed shared lane is assigned before editing it.

## Live contract check ownership

The coordinator owns RelayNativeWireExportTest, core/engine/build.gradle.kts opt-in output declaration, relay-android/src/kotlin_contract_tests.rs and its lib.rs module declaration, scripts/tests/run-relay-config-contract.py, the just recipe, and the cross-language release-gate inventory and validator tests. The generated wire file is a disposable Gradle output, not a checked-in golden.

## F2 refresh ownership

The Kotlin worker also owns app SubscriptionRefreshCoordinator.kt and SubscriptionReserveRefreshTest.kt. The final caller check found a VLESS-only guard that bypasses shared validation for Trojan and AnyTLS. This remains the same F2 atomic fix. The worker also owns the minimum validator decomposition required by the existing LongMethod gate; no baseline or suppression changes.

The coordinator owns the comment-only RelayKindDescriptor.kt lane to distinguish the separate Apps Script Rust runtime from relay-core registry membership. Registry rows and capabilities stay unchanged.

## Live gate CI ownership

The Kotlin worker owns the minimum CI wiring for the live contract command in .github/workflows/ci.yml and the owning routing regression test. It uses the existing Android/Rust setup, runs on Android or native changes, and is required by ci-required. The coordinator keeps ownership of the producer, consumer, script and policy inventory. No CI trigger, secret, baseline or release policy changes.
