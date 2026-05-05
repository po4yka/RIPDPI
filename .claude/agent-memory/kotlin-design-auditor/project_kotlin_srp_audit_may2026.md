---
name: kotlin_srp_audit_may2026
description: SRP / god-class / DIP audit findings (May 2026) covering new violations not tracked by epic-srp-and-architecture-refactoring. 10 new findings across ViewModels, Compose screens, Hilt scoping, coroutines, and DIP.
type: project
---

Audit completed 2026-05-05 against main branch.

**Why:** epic-srp-and-architecture-refactoring tracks 10 known hotspots. This audit finds net-new violations outside that list.
**How to apply:** Use as the backlog input for the next SRP epic iteration. Re-check findings 1, 2, 4, 7, 9 first (P2).

## Known baseline (already tracked, do NOT re-report)
VpnServiceRuntimeCoordinator, DefaultConnectionPolicyResolver, OwnedStackBrowserService,
SettingsUiModels.kt, AdvancedSettingsScreen.kt, DiagnosticsUiStrategySupport.kt,
DetectionCheckScreen.kt, HomeAnalysisPanels.kt, RipDpiState.kt, UpstreamRelaySupervisorSupport.kt

## New findings (10 items)

1. ConfigViewModel (P2) — 542 LOC, 11 constructor params, 4 distinct responsibilities:
   draft editing, relay credential persistence, capability observation, network fingerprinting.
   Suggested split: ConfigDraftEditorViewModel + RelayCredentialPersister + CapabilityObserver.

2. DetectionCheckViewModel (P2) — 392 LOC, 7 constructor params, extends AndroidViewModel (raw Application in constructor).
   Mixed: permission management, community stats fetching (direct HTTP via CommunityComparisonClient), detection run orchestration, history persistence, settings auto-fix application.
   Suggested split: DetectionRunViewModel + CommunityStatsViewModel (or UseCase) + DetectionPermissionHelper.

3. LogsViewModel (P3) — 483 LOC, 3 constructor params.
   Mixed: log aggregation from 4 sources, filtering, formatting (SimpleDateFormat inline), service lifecycle observation.
   Suggested split: LogAggregator (domain) + LogsViewModel (thin UI layer).

4. OnboardingViewModel (P2) — 442 LOC, 5 constructor params.
   Mixed: permission resolution, VPN consent orchestration, mode/DNS selection persistence, traffic validation lifecycle.
   Suggested split: OnboardingPermissionCoordinator + OnboardingViewModel (pagination + UI state only).

5. DesyncSection.kt (P3) — 843 LOC Compose extension function on LazyListScope.
   Single function holds: adaptive TTL editing, fake-TLS profile selection, chain DSL editing, OOB data, IP-ID mode, fake payload library — 7 logically independent sub-sections.
   Suggested split: one composable per sub-section (AdaptiveTtlSection, FakeTlsSection, FakePayloadSection, ChainEditorSection).

6. ModeEditorScreen.kt + RelayFields.kt (P3) — 745 LOC each (1490 LOC combined for a single screen).
   RelayFields.kt is a monolith of relay-type-specific field rendering (VLESS, Hysteria2, MASQUE, Snowflake, etc.) embedded in one file.
   Suggested split: one file per relay kind (RelayVlessFields, RelayHysteria2Fields, RelayMasqueFields, …) behind a RelayFieldsFactory dispatcher.

7. DiagnosticsUiModels.kt (P2) — 860 LOC, single file holding: domain enums, 30+ @Immutable/@Stable UI model data classes, extension properties, and SRP-violating business logic helpers.
   Contains DpiFailureClass.label, DiagnosticsProfileOptionUiModel.isStrategyProbe/isFullAudit — business derivation that belongs in a mapper/use-case, not in a UI model file.
   Suggested split: DiagnosticsUiEnums.kt + DiagnosticsUiModels.kt (pure data) + DiagnosticsUiModelMappers.kt.

8. LaunchedEffect(Unit) collecting SharedFlow effects — 3 sites (P3):
   - AdvancedSettingsRoute.kt:58 — collects viewModel.effects with LaunchedEffect(Unit); if recomposed with a new ViewModel instance the old coroutine continues until cancellation.
   - ModeEditorRoute.kt:135, 161 — two separate LaunchedEffect(Unit) blocks for the same ViewModel's effects flow.
   Correct key is LaunchedEffect(viewModel) (as done correctly in DiagnosticsRoute.kt:43). Both sites should be keyed on viewModel.

9. DetectionHistoryStore (P2) — @Singleton class in :core:detection that owns write-through SharedPreferences I/O synchronously on the calling thread.
   No interface; DetectionCheckViewModel and CommunityComparisonClient reference the concrete class directly across the call stack.
   Missing: interface DetectionHistoryRepository; @Binds binding; IO dispatcher offloading.

10. CommunityComparisonStore (P3) — not injectable (no @Inject, no Hilt binding); instantiated with raw Context twice:
    - DetectionCheckViewModel.kt:84 (lazy delegate, raw Application passed)
    - SettingsViewModel.kt:158 (inside launch block, raw Application via settingsUiDependencies.application)
    Each call site creates a separate instance — no shared cache state. Should be @Singleton + @Inject constructor(@ApplicationContext).

## Hilt scope summary
101 modules in SingletonComponent, 2 in VpnServiceSessionComponent, 2 in ProxyServiceSessionComponent,
2 in BootstrapProxySessionComponent, 1 in ViewModelComponent, 1 in ActivityComponent.
Ratio: heavily skewed to Singleton — session-lifecycle types (capability stores, relay credential runtime) not re-scoped.

## @Binds/@Provides ratio
@Binds count: 26 (from grep; all in core modules)
@Provides count: ~290
Ratio: ~1:11 — very low @Binds usage; most domain types are @Provides of concrete classes.
