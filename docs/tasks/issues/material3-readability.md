---
id: UIX-1791793531264679
title: Improve Material 3 readability and navigation
kind: feature
status: review
area: ui
priority: high
owner: ui-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: material3-readability
created: 2026-10-12
updated: 2026-10-13
status_detail: Installed API 37 ARM64 acceptance passed 88 captures in both themes at 200% font. UI/activity tests, lint, static analysis, native APK, and strict Full/Simple screenshot gates passed. Main integration is authorized; no push is owned.
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

## Visual follow-up ownership

Fresh Russian captures at 320dp and 2x font showed remaining compressed words after the combined source gate. Follow-up writers use new isolated worktrees from `8ccfdfa2f`.

- Shared writer: WarningBanner, RipDpiAccordion, and their strict layout tests.
- Forms writer: RelayMasqueAuthFields and the nested-card regression in ReadabilityFormsLayoutTest.
- Data writer: OomRecoveryScreen, StrategyImportScreen, DiagnosticsWidgets, StateMachineScreen pixel rounding, and ReadabilityDataScreenTest.
- Integration owner: RipDpiTopAppBar, Type.kt, RipDpiTheme.kt, their tests, preview viewport corrections, full capture heights, documentation, and combined review. Sans text may use system hyphenation when a word exceeds the complete available line; typography sizes and monospace values remain unchanged.
- Golden fixtures remain owned only by golden-blesser after fresh visual acceptance.
- Shared follow-up writer: RipDpiTopAppBar, its tests, and the two scaffold action defaults in RipDpiScaffolds. Remove the empty large-font controls row when no controls exist.
- Golden-blesser also owns only the capture heights for handshake timeline, state machine, strategy import, and quality graph screenshot tests. Extend these canvases to inspect all stage rows, diagram legends, import actions, and graph captions; do not change their data or production behavior.

## Installed emulator follow-up ownership

- Runtime labels writer: app DNS summary presentation, all theme label locale arrays, and their focused tests in the isolated fix/readability-runtime-labels worktree. This writer alone owns locale resources for this pass. Core DNS summary contracts and persisted theme entries remain unchanged.
- Device acceptance agent: the leased API 37 ARM64 Pool_1 emulator only; install, navigation, both-theme 200% captures, and runtime receipts. The existing emulator-5586 is outside this task. Integration owner: DESIGN.md, audit evidence, task/OpenSpec artifacts, combined validation, and authorized main integration.
- Read-only visual reviewer: actual emulator PNG captures; no device interaction or source changes.
- Golden fixtures remain serialized to golden-blesser if the runtime repair changes existing fixtures; prior user approval covers affected UI readability fixtures.

- Report rows writer: DiagnosticsCards.kt session, probe, and event rows plus focused Compose layout tests, in the isolated fix/readability-report-rows worktree. Keep report metadata, status semantics, callback, and automation tags unchanged. No shared tokens or locale writes.

- Integration owner also owns LauncherIconManager preview background metadata, AppCustomizationScreen and IconPickerGrid preview backgrounds, and their focused tests. Use existing adaptive-icon backgrounds; launcher assets and selection aliases remain unchanged.

- Report rows writer also owns ConfigScreen.kt endpoint chips and their focused layout regression test. The installed Russian proxy choice must keep whole-word width at 200% and preserve VPN/proxy selection.

- Locale refresh writer: MainActivityContent locale observation, MainViewModel/MainUiStateOwner localized presentation invalidation, and focused state tests in fix/readability-locale-refresh. The installed permission guidance must update when app language changes without process restart; permission state and service behavior remain unchanged.

- Locale refresh writer also owns MainPermissionUiFactories.kt only for rebuilding localized issue text while preserving permission kind, recovery, and blocking semantics.

- After the report rows writer reached model capacity, the integration owner completes its remaining tests and scoped fixes in the same isolated report-rows worktree. The writer is inactive; source ownership remains serialized.

- Report status writer: DiagnosticsStrategyProbeReport.kt confidence and candidate headline rows plus focused layout tests, in the isolated fix/readability-report-status worktree. Use measured word budgets and stack status when needed. Preserve data, rank, eligibility, labels, callbacks, and report tags. The integration owner retains shared tokens, locales, fixtures, and all other files.

- The report status writer froze and handed off both files with SHA-256 receipts. The integration owner is now the sole writer for those files and includes them in the combined UI gate before the atomic report commit.
