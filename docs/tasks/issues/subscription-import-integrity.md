---
id: DAT-1794720910687853
title: Preserve subscription import semantics and selector defaults
kind: bug
status: doing
area: data
priority: high
owner: Import integrity integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: subscription-import-integrity
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Preserve the meaning of supported subscription inputs. Reject input that cannot be imported without data loss. Store selector defaults without starting a connection.

## Acceptance criteria

- F1-F11 have independent regression coverage and one atomic fix commit per finding.
- Invalid updates preserve the previous members, package routes, and selection.
- Bootstrap and ordinary imports use the same node admission policy and selector membership.
- Unsupported route, trust, and transport constraints cause an explicit error.
- Xray credentials follow the pinned upstream precedence.
- Selector defaults survive storage and do not replace an explicit user selection.
- Targeted JVM tests, affected lint, task checks, and just ci-gates pass. Native metadata and architecture checks pass before integration.

## Ownership

- Parser writer: SingBoxRouteRulesParser.kt (F1), ClashSubscriptionParser.kt (F2), SingBoxSubscriptionParser.kt (F3/F5), and separate owning regression tests. These files have one writer.
- Xray writer: XrayConfigImportParser.kt, its helpers and owning tests (F4/F7). Manual input budgets, background validation, and stale-result tests stay in this lane. The Xray writer alone owns any required locale key and translation export for this size error.
- Selector writer: BootstrapConsumer.kt, SelectorUrltestGroupImport.kt, SelectorTopology.kt, ProxyGroupStores.kt, subscription persistence/selection callers, and owning tests (F6/F8/F9/F10). All saved selector fields have this single writer.
- Integrator: SubscriptionPayloadLimits.kt and its decoding tests (F11), task/OpenSpec artifacts, combined gates, review, integration, and push.
- Integration quality writer: only DiagnosticsTelegramPresentation.kt and DiagnosticsTelegramCards.kt for behavior-preserving extraction required by inherited detekt blockers from incoming main; preserve existing Telegram output tests. This is not an audit of the adjacent diagnostics feature.
- Diagnostics quality writer: only core/diagnostics DiagnosticsLocalNetworkPreflight.kt, application/DiagnosticsPlanningServices.kt, TelegramSummary.kt, DiagnosticsScanRequestFactoryTest.kt, DiagnosticsLocalNetworkPreflightTest.kt, DiagnosticsModelsCompatibilityTest.kt and a test split if needed. Remove ten inherited detekt blockers with behavior-preserving extraction, named constants and formatting. Preserve all existing test assertions.
- Incoming diagnostics quality writer: only app DiagnosticsTransferUiModel.kt and core/diagnostics dpich/SnowflakeStunReachabilityProbe.kt, dpich/PluggableTransportReachabilityProbe.kt, ComparisonScanCoordinator.kt, TransferProgressModels.kt and TransferArchiveEvidenceTest.kt. Remove seventeen inherited detekt blockers from incoming DIA-10/12 with minimal control-flow extraction and constants. Preserve cancellation, exception propagation, evidence parsing and tests; no adjacent full audit.
- Shared-file lanes: no dependency, golden, protobuf, or Rust registry change is planned. The Xray writer owns the size-error key in all ten locales and its translation export. Any other shared-file change is serialized through the integrator. Each writer uses a separate worktree. No writer edits another lane.

## Scope

Fix the eleven audit findings. Recheck hypotheses H1-H4 and fix a confirmed local defect in the owning lane. Do not add a new transport runtime, full external-config exporter, or external/device acceptance dependency.
