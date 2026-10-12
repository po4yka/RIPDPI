---
id: UIX-1791793531264679
title: Improve Material 3 readability and navigation
kind: feature
status: doing
area: ui
priority: high
owner: ui-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: material3-readability
created: 2026-10-12
updated: 2026-10-12
status_detail: User requested app-wide fixes for the remaining readability, icon, and adaptive layout defects. Three source lanes own shared controls, forms, and data screens.
---

## Goal

Make app text, standalone and leading icons, settings, navigation, forms, and diagnostic data layouts readable at standard and large font sizes.

## Acceptance criteria

- Primary UI text uses 16/24sp; supporting settings text uses 14/20sp; primary action and navigation icons use 24dp.
- Settings values wrap without reserving half of each row for short actions.
- Diagnostics sections use accessible Material 3 tabs without a vertical fixed selector at large font scale.
- Local UI tests and lint pass; fresh light, dark, and 2x font captures are inspected.
- DESIGN.md records the revised roles and layouts.
- Shared actions keep complete labels and 48dp targets; choice groups and localized facts wrap without compressed words.
- Data cards, recovery actions, graph headers, and scrollable diagrams keep visible content and preserve callbacks, selection, and coordinate alignment in RTL.

## Ownership

- typography worker: Type.kt, RipDpiIcons.kt, HomeModeCard.kt, ProfileUtilityScreen.kt, typography tests.
- typography follow-up worker: OnboardingOptionCard.kt and OnboardingOptionCardTest.kt in a separate fix/material3-option-reflow worktree; prevent title/badge compression after the shared type increase.
- settings worker: SettingsRow.kt and SettingsRowTest.kt; follow-up ownership includes RipDpiRowMetrics in Spacing.kt for a 40dp leading badge, 32dp leading symbol, and 24dp trailing chevron. This writer alone owns these row metrics.
- diagnostics worker: DiagnosticsOverviewSection.kt, DiagnosticsSectionSwitcherTest.kt, diagnostics preview functions.
- integration owner: DESIGN.md, docs/design-system.md, task and OpenSpec artifacts, combined-tree validation and preview captures.
- golden-blesser: app/src/test/screenshots in the isolated test/material3-ui-goldens worktree; user approved the affected UI fixtures on 2026-10-12.
- Serialized: golden-blesser owns screenshot fixtures, the typography worker owns type and icon tokens, the settings worker owns row metrics, and the integration owner owns the generated board. These paths have one writer each. Other workers do not update fixtures, locales, or planning artifacts.
- Each writer uses a separate worktree. Main integration and commits are authorized by the user; push is not requested.

## App-wide follow-up ownership

The user requested all analogous defects after the first verified stage at 15b634db6.

- Shared controls writer: ui/theme/Spacing.kt; ui/components/buttons (including RipDpiTextAction); RipDpiChip; WarningBanner, RipDpiDegradationStrip, RipDpiContextMenu, RipDpiSnackbar, RipDpiAccordion; BottomNavBar; their tests; ReadabilityAuditSharedPreviews.kt. This writer alone owns shared metrics.
- Forms writer: DnsSettingsCards; RuleEditorScreen; SubscriptionFailoverScreen; RememberedNetworksScreen; AboutScreen; ModeEditorRelaySection, RelayFields, RelayMasqueAuthFields, RelayVlessTransportFields, RelayCloudflareTunnelFields, RelayTuicFields, RelayMasqueMtlsFields, DomainBypassListScreen, WsTunnelWorkerSettingsUi, SettingsPreferenceSections; their tests; ReadabilityAuditFormsPreviews.kt.
- Data writer: OomRecoveryScreen, StrategyImportScreen, DiagnosticsWidgets, DiagnosticsToolsSection, ThroughputGraphScreen, LatencyGraphScreen, QualityGraphsScreen, StateMachineScreen, PortMatrixScreen, HandshakeTimelineScreen, HistoryFilters, HistorySections, LogsScreen, HomeAnalysisBottomSheets, ConnectionHealthScreen, ProfileVariantsScreen, BlockcheckRoute, DiagnosticsIpv4WhitelistCard; their tests; ReadabilityAuditDataPreviews.kt.
- Integration owner: DESIGN.md, docs/design-system.md, audit and verification reports, task/OpenSpec artifacts, combined-tree gates, and main integration. After the forms agent availability failure, the integration owner completes its final DNS header correction, validation, and scoped commit in the same isolated forms worktree; the agent is inactive. After the data agent availability failure, the integration owner completes the HistoryEventsFilterCard extraction, validation, and scoped commit in the same isolated data worktree; that agent is inactive.
- Golden-blesser alone owns app/src/test/screenshots. The existing user approval covers the affected readability UI family; no other fixture family is owned here.
- Each writer uses a clean isolated worktree. Shared metrics land first; forms and data writers incorporate that commit before their final tests. All metadata, fixtures, and locale sets remain serialized.
