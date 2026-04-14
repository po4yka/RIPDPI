# Jetpack Compose Audit Report

Target: `/Users/po4yka/GitRep/RIPDPI` — module `:app`
Date: 2026-04-14
Scope: `app/src/main/kotlin/com/poyka/ripdpi/ui/**` and `app/src/main/kotlin/com/poyka/ripdpi/activities/**` (ViewModels/UiState)
Excluded from scoring: `**/@Preview` bodies, `**/testing/**`, `:baselineprofile/**`, `:core:**` (no Compose)
Confidence: Medium
Overall Score: 71/100
Design system: RipDpiThemeTokens (custom wrapper over MaterialTheme) — see `.claude/skills/material-3/` for design scoring

Track context: Kotlin 2.3.20 · Compose Compiler 2.3.20 · Compose BOM 2026.03.01 · Strong Skipping default on · Navigation Compose 2.9.7 · kotlinx-collections-immutable 0.4.0. `app/compose-stability.conf` is wired via the `ripdpi.android.compose` convention plugin.

## Scorecard

| Category | Score | Weight | Status | Notes |
|----------|-------|--------|--------|-------|
| Performance | 6/10 | 35% | needs work | Strong stability annotations across UiState/UiModel, but systemic raw `List<T>` in @Composable params on hot screens (Diagnostics cards, charts, scan section, Home analysis indicator). Performance ceiling capped at **7** (Step 4 fallback — compiler reports not available for this run). |
| State management | 8/10 | 25% | solid | Clean UDF, `stateIn(WhileSubscribed(5_000))`, `collectAsStateWithLifecycle` everywhere, proper `rememberSaveable`. One-shot events over `Channel.BUFFERED` is the main gap. |
| Side effects | 8/10 | 20% | solid | Deliberate effect API choices, `rememberUpdatedState` used for effect callbacks, `DisposableEffect` with matching cleanup. |
| Composable API quality | 7/10 | 20% | solid | Modifier conventions correct across 16 shared components; zero `MutableState<T>` params; 100% preview coverage of reusable leaves. Loses points on hardcoded `dp` inside shared components and `List<T>` on two design-system leaves. |

Overall computation: `0.35*6 + 0.25*8 + 0.20*8 + 0.20*7 = 7.10` → **71/100**.

## Critical Findings

1. **Performance (systemic): raw `List<T>` parameters on @Composable functions that run on hot paths**
   - Why it matters: Compose treats raw `List` / `Set` / `Map` as unstable and cannot skip recomposition when the reference changes even if contents are equal. Every parent recomposition that forwards a `List` triggers full rebuilds of downstream subtrees. The project pervasively uses `ImmutableList` inside `UiState` classes (`DiagnosticsUiState`, `LogsUiState`, `HistoryUiState`), but the Composable signatures silently erase that stability when they accept `List<T>` back.
   - Evidence:
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:274` `MetricsRow(metrics: List<DiagnosticsMetricUiModel>)`
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:419,491,602,620,1052` — chart/trend/metric surfaces
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCharts.kt:9-38` `values: List<Float>` on every spark-line helper
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsLiveSection.kt:198` `LiveHighlightsGrid(highlights: List<DiagnosticsMetricUiModel>)`
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/AnalysisProgressIndicator.kt:65,186` `stages: List<AnalysisStageUiState>` — rendered on Home during scans
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiDropdown.kt:55,206,246` — design-system component
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/LogRow.kt:37` `metadataChips: List<String>` — per-row in LogsScreen
     - Upstream: `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt:112` `AnalysisProgressUiState.stages: List<AnalysisStageUiState>` is declared raw too, so even the `@Immutable` wrapper leaks unstable shape.
   - Fix direction: Change parameter types to `ImmutableList<T>` / `PersistentList<T>`; update upstream UiState fields to `ImmutableList` to carry stability end-to-end. The stability config already marks `kotlinx.collections.immutable.*` stable.
   - References: <https://developer.android.com/develop/ui/compose/performance/stability/fix>, <https://developer.android.com/develop/ui/compose/performance/stability>

2. **Performance: `LogsUiState.filteredLogs` is a recomputing getter read multiple times per composition**
   - Why it matters: `LogsUiState` is `@Immutable` but `filteredLogs: List<LogEntry>` is a computed `get()` that filters the full buffer on every read (`activities/LogsViewModel.kt:93-99`). The UI reads it at least three times per recomposition (`LogsScreen.kt:113` for the LazyColumn, `LogsScreen.kt:329` `latestLog = uiState.filteredLogs.lastOrNull()`, and `LogsScreen.kt:131` in the auto-scroll key set). Each read allocates a fresh `ArrayList`, meaning the `filteredLogs.size` and `contains(latestLog)` checks never stabilise and the `LazyColumn` sees a new list identity every composition.
   - Evidence:
     - `app/src/main/kotlin/com/poyka/ripdpi/activities/LogsViewModel.kt:93-99`
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/logs/LogsScreen.kt:113,131,329`
   - Fix direction: Compute `filteredLogs` once per emission inside the `combine` pipeline, expose as `ImmutableList<LogEntry>` on `LogsUiState`. Remove the getter.
   - References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>

3. **State management: one-shot events exposed via unbounded `Channel.BUFFERED` + `receiveAsFlow()`**
   - Why it matters: `receiveAsFlow()` is collected only while a composable is active. During configuration changes or background/foreground gaps the channel keeps buffering (unbounded) but the collector is gone; the next collector may either receive a backlog all at once or (depending on timing) lose events silently if the channel is closed. Canonical UDF guidance prefers `MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)` for event streams with clear overflow semantics.
   - Evidence:
     - `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt:60-62`
     - `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt:280-282`
     - `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsViewModel.kt:64-70`
     - `app/src/main/kotlin/com/poyka/ripdpi/activities/OnboardingViewModel.kt:59-60`
     - `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt:121-122`
     - `app/src/main/kotlin/com/poyka/ripdpi/activities/MainActivityShellController.kt:36-41`
   - Fix direction: Replace `Channel<Effect>(BUFFERED)` + `receiveAsFlow()` with a buffered `SharedFlow` and an explicit overflow policy; document whether events may legitimately drop.
   - References: <https://developer.android.com/develop/ui/compose/architecture>

4. **State management: string-based navigation routes on Navigation Compose 2.9.7**
   - Why it matters: Navigation Compose ≥ 2.8 supports type-safe `@Serializable` routes with compile-time checked arguments. The project is on 2.9.7 and uses `kotlinx-serialization` 1.11.0, but still defines routes via string DSL (`Route.Home.route`, `composable(Route.Diagnostics.route)`). This encourages argument-encoding bugs and loses refactoring safety.
   - Evidence:
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt` (string route definitions)
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt:247-404` (20+ `composable("...")` call sites)
   - Fix direction: Migrate to `@Serializable` route classes; replace `navigate(route.route)` with `navigate(Route.Foo)`.
   - References: <https://developer.android.com/develop/ui/compose/navigation>

5. **Composable API Quality: hardcoded `dp` and accessibility strings in shared design-system leaves**
   - Why it matters: The project has a disciplined theme token system (`RipDpiThemeTokens.colors/spacing/type/layout/components`). Sprinkling raw `dp` and `sp` values in the design-system layer defeats the theme's ability to scale for density, accessibility, or re-skin, and introduces magic numbers that diverge from the rest of the code.
   - Evidence:
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/StatusIndicator.kt:119,122,129,135,148,154` — canvas marker sizes as literals (8/9/10 `.dp`)
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiChip.kt:75,80,159` — `components.chipHorizontalPadding - 4.dp`, icon `Modifier.size(14.dp)`
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiTextField.kt:88,196` — literal `6.dp` label gap, literal `1.dp` comparison
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiDropdown.kt:89` — `Arrangement.spacedBy(6.dp)`
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiBottomSheet.kt:173` — `padding(top = 12.dp)` handle
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/buttons/RipDpiButton.kt:104,173` — literal offsets
     - `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiSwitch.kt:149` — `stateDescription = if (checked) "On" else "Off"` bypasses `stringResource` and breaks i18n + a11y strings.
   - Fix direction: Add token fields (e.g., `components.indicatorDotSmall/Medium/Large`, `components.chipIconSize`, `spacing.xxs`); replace the hardcoded `"On"/"Off"` with `stringResource(R.string.common_on/off)`.
   - References: <https://developer.android.com/develop/ui/compose/designsystems/material3>, <https://developer.android.com/develop/ui/compose/resources>

## Category Details

### Performance — 6/10

**What is working**

- `@Immutable`/`@Stable` annotations are applied to 98 classes across 16 files (UI models, sealed states, theme tokens). `DiagnosticsUiModels` alone exposes 48 `UiState`/`UiModel` classes and every one is annotated.
- `app/compose-stability.conf` marks third-party types stable (protobuf, `java.time`, `kotlin.time.Duration`, `kotlinx.collections.immutable.*`, and the core:data `*Model` classes). Wired via `build-logic/convention/src/main/kotlin/ripdpi.android.compose.gradle.kts`.
- Strong Skipping Mode default is respected — no `@NonSkippableComposable` or `@DontMemoize` annotations anywhere.
- Typed primitive state factories used correctly: `mutableIntStateOf(-1)` keyed on `trend.label` in `DiagnosticsCards.kt:347` and `mutableLongStateOf(0L)` in `BiometricPromptScreen.kt:78`.
- `derivedStateOf` used correctly (all three usages actually read `State`): `HistoryScreen.kt:551`, `DiagnosticsScreen.kt:1089`, `LogsScreen.kt:121` — all compute `isAtLiveEdge` from scroll info.
- Lazy lists almost universally provide `key =` and `contentType =`: `HistoryScreen.kt:456,525,622`, `DiagnosticsScreen.kt:866,1037,1159,1172,1199`, `DiagnosticsLiveSection.kt:76,91,103`.
- `MainActivity.kt:76` uses first-party `enableEdgeToEdge()`, not `accompanist-systemuicontroller`.
- `:baselineprofile` module exists with a generator and startup benchmark wired into CI.
- No use of the deprecated `animateItemPlacement()` (Compose 1.7 `Modifier.animateItem` era).
- No `Modifier.composed { }` usage — consistent with the `Modifier.Node` guidance.
- `@ReadOnlyComposable` used deliberately in `DiagnosticsScanSection.kt:1337`.
- `TrackRecomposition("…")` instrumentation present on `HomeScreen.kt:221` and `DiagnosticsScreen.kt:314`.

**What is hurting the score**

- Systemic raw `List<T>` in reusable / hot-path Composables — see Critical Finding 1.
- `LogsUiState.filteredLogs` recomputes per read — see Critical Finding 2.
- Two design-system leaves (`RipDpiDropdown.kt:55`, `LogRow.kt:37`) accept `List<T>` despite being consumed everywhere a ViewModel exposes `ImmutableList`.
- `AdvancedSettingsBinder.kt:323,346,713` call `indexOf` / `indexOfFirst` on domain collections; this is not inside `LazyListScope` so it does not hit the quadratic-scroll heuristic, but it is O(n) work per composition — acceptable here.

**Measured ceilings**

Compiler reports for this run could not be materialised: the initial `:app:assembleRelease -Pripdpi.composeReports=true` run completed in 6m 17s but was ~290/315 tasks up-to-date (Kotlin compile was cached, so `reportsDestination` never fired). A `--rerun-tasks` follow-up was still building at report time. Per Step 4 fallback, qualitative Performance is capped at **7** for this run and stability-inferred findings above are labelled as source-inferred. The report can be refreshed with measured `skippable%` by running:

```
./gradlew :app:compileReleaseKotlin -Pripdpi.composeReports=true --rerun-tasks
```

then reading `app/build/compose-reports/*-composables.csv` (computing named-only `skippable = sum(skippable) / sum(restartable)` with `isLambda == 0`) and `*-module.json`.

**Evidence**

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:274,419,491,602,620,1052` — raw `List` on chart/metric composables · References: <https://developer.android.com/develop/ui/compose/performance/stability/fix>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCharts.kt:9,10,12,38` — `List<Float>` on each chart helper · References: <https://developer.android.com/develop/ui/compose/performance/stability>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/AnalysisProgressIndicator.kt:65,186` and `MainViewModel.kt:112` — unstable `stages: List<…>` surface that renders on Home during scans · References: <https://developer.android.com/develop/ui/compose/performance/stability/fix>
- `app/src/main/kotlin/com/poyka/ripdpi/activities/LogsViewModel.kt:93-99` — recomputing filtered getter · References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/logs/LogsScreen.kt:464` — `itemsIndexed` lacks `contentType = { … }` · References: <https://developer.android.com/develop/ui/compose/lists>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScanSection.kt` — probe-key scheme noted in `compose-performance` quick-wins still open · References: <https://developer.android.com/develop/ui/compose/lists>
- Positive: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/history/HistoryScreen.kt:456,525,622` — key + contentType on every items() · References: <https://developer.android.com/develop/ui/compose/lists>

### State Management — 8/10

**What is working**

- Every ViewModel exposes a single `StateFlow` converted with `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)` — `LogsViewModel.kt:207-211`, `MainViewModel`, `DiagnosticsViewModel`, `HistoryViewModel`, `SettingsViewModel`, `ConfigViewModel` (36 `stateIn(` occurrences across 9 files).
- All UI collection uses `collectAsStateWithLifecycle()`; zero calls to raw `collectAsState(` anywhere under `app/src/main/kotlin`. Verified by greps in this audit.
- `rememberSaveable` is used for the kind of state that must survive recreation — dialog visibility, picker selections, PIN drafts, tab ordinals — and always with inputs Compose can serialise into the `Bundle` (strings, primitives, sets of strings). `AdvancedSettingsComponents.kt:150` correctly keys `rememberSaveable(value) { mutableStateOf(value) }` so the cache invalidates when upstream changes.
- ViewModels are entered at screen entry points via `hiltViewModel()`; they are never hoisted through `CompositionLocal` (no `compositionLocalOf { vm }` patterns). Nested graph ViewModels correctly scope to graph entries via `navController.getBackStackEntry(ConfigGraphRoute)`.
- `CompositionLocal` is used only for *tree-scoped* theme data (`LocalRipDpiExtendedColors`, `LocalRipDpiSpacing`, `LocalRipDpiLayout`, `LocalRipDpiMotion`, `LocalRipDpiShapes`, `LocalRipDpiTextStyles`), all declared with `staticCompositionLocalOf` and sensible defaults — exactly the pattern the docs recommend.
- UiState layer uses `ImmutableList`/`ImmutableSet` from kotlinx-collections-immutable (e.g. `LogsUiState.logs: ImmutableList<LogEntry>`, `DnsSettingsScreen.kt:91 bootstrapIps: ImmutableList<String>`, `HistoryUiModels`, `DiagnosticsUiModels`).

**What is hurting the score**

- One-shot events exposed via `Channel.BUFFERED` + `receiveAsFlow()` — see Critical Finding 3.
- String-based navigation routes despite having the type-safe option available — see Critical Finding 4.
- `filteredLogs` getter on `LogsUiState` (counted under Performance, not double-deducted here, but it is also a state-shape smell: a derived value should be materialised).

**Evidence**

- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt:60,62` — `Channel.BUFFERED` + `receiveAsFlow()` · References: <https://developer.android.com/develop/ui/compose/architecture>
- `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt:280,282` — same pattern · References: <https://developer.android.com/develop/ui/compose/architecture>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt`, `RipDpiNavHost.kt:247-404` — string routes on Navigation Compose 2.9.7 · References: <https://developer.android.com/develop/ui/compose/navigation>
- Positive: `app/src/main/kotlin/com/poyka/ripdpi/activities/LogsViewModel.kt:189-211` — `stateIn(WhileSubscribed(5_000))` with initial state · References: <https://developer.android.com/develop/ui/compose/architecture>
- Positive: `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Color.kt:327-328`, `Spacing.kt:165-167`, `Type.kt:148`, `RipDpiMotion.kt:91`, `Shape.kt:35` — `staticCompositionLocalOf` with sensible defaults · References: <https://developer.android.com/develop/ui/compose/compositionlocal>

### Side Effects — 8/10

**What is working**

- `DiagnosticsScreen.kt:135-138` captures effect callbacks with `rememberUpdatedState` before collecting the event flow in `LaunchedEffect(Unit) { viewModel.effects.collect { … } }` (lines 141-211) — textbook handling of stale lambda capture.
- `DisposableEffect(Unit) { … onDispose { … } }` in `BiometricPromptScreen.kt:151-155` toggles `FLAG_SECURE` on the window with a matching clear — correct symmetric cleanup.
- Navigation calls live exclusively in event-handler lambdas or nested inside `LaunchedEffect { … }` blocks; there are zero `navController.navigate(…)` calls in composition bodies (all 21 usages in `RipDpiNavHost.kt` are inside callback closures).
- `rememberCoroutineScope()` is confined to event-driven concerns (snackbar copy in `LogsScreen.kt:117`, countdown launch in `BiometricPromptScreen.kt:70`) — not reinvented cancellation for keyed work.
- `LaunchedEffect(Unit)` / `LaunchedEffect(viewModel)` patterns are used only for single-shot initialisation whose bodies do not capture changing parameters (e.g. `DiagnosticsScreen.kt:114` `viewModel.initialize()`, `HistoryScreen.kt:79`). Where parameters could change, `rememberUpdatedState` is used (see above).
- `LogsScreen.kt:131` `LaunchedEffect(uiState.isAutoScroll, uiState.latestLog?.id, filteredLogs.size)` correctly keys on the values that should drive the scroll side-effect.
- No side effects in composition: no `launch { … }` at composable scope, no `withContext`, no direct coroutine dispatch, no `Dispatchers.IO` calls from `@Composable` bodies.

**What is hurting the score**

- The event-flow collector runs inside `LaunchedEffect(Unit)` in `DiagnosticsScreen.kt:141` — this alone is fine, but the choice of a buffered `Channel` upstream (see Critical Finding 3) makes the effect semantics less robust than they look.
- `DiagnosticsScreen.kt:122-126` calls `pagerState.animateScrollToPage(…)` from a `LaunchedEffect` keyed on `uiState.selectedSection`. If the user is mid-drag this will conflict with the ongoing gesture; the existing guard `if (pagerState.currentPage != uiState.selectedSection.ordinal)` helps but does not synchronise with interaction state.

**Evidence**

- Positive: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt:135-211` — `rememberUpdatedState` + `LaunchedEffect(Unit)` collector pattern · References: <https://developer.android.com/develop/ui/compose/side-effects>
- Positive: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/permissions/BiometricPromptScreen.kt:151-154` — `DisposableEffect` cleanup · References: <https://developer.android.com/develop/ui/compose/side-effects>
- Positive: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/logs/LogsScreen.kt:131-138` — correctly keyed effect · References: <https://developer.android.com/develop/ui/compose/side-effects>
- Note: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt:122-126` — pager sync ignores drag state · References: <https://developer.android.com/develop/ui/compose/side-effects>

### Composable API Quality — 7/10

**What is working**

- `modifier: Modifier = Modifier` appears on every shared composable audited (16/16 design-system files). In every case it is placed as the first optional parameter and applied as the first chain link on the root emitted node. Zero shared components apply the modifier to a non-root child. (Verified file-by-file by an independent `Explore` pass.)
- Zero public Composable APIs accept `MutableState<T>` or `State<T>` parameters. Every interactive widget uses the `value: T` + `onValueChange: (T) -> Unit` shape.
- Receiver scopes and slot APIs are used correctly — `RipDpiBottomSheet` exposes a `content: @Composable ColumnScope.() -> Unit` slot; `RipDpiCard` and `RipDpiScaffolds` expose slot-style `content` lambdas with the right scope.
- Preview coverage is strong: 15 of 16 shared components ship at least one `@Preview`; most ship both light and dark variants.
- All colour resolution routes through `RipDpiThemeTokens.colors.*` or `MaterialTheme.colorScheme.*` — zero raw `Color(0xFF…)` literals in any component body (only in `ui/theme/Color.kt`, which is the intended place).
- Custom `ripDpiClickable`/`ripDpiToggleable`/`ripDpiSelectable` wrappers are used consistently — there are no naked `Modifier.clickable` calls in screens that would bypass the haptic/semantics contract.
- `Scaffold { innerPadding -> … }` content correctly applies `innerPadding` to a root child in every wrapper (`RipDpiScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`).
- `stringResource(R.string.*)` is used for every user-visible string inside shared components (except the `"On"/"Off"` case below).

**What is hurting the score**

- Hardcoded `dp` / `sp` values in reusable components — see Critical Finding 5.
- `RipDpiSwitch.kt:149` uses literal `"On"/"Off"` for `stateDescription` — breaks i18n and is the only hardcoded user-facing string found in shared code.
- `RipDpiDropdown.kt:55` and `LogRow.kt:37` take raw `List<T>` parameters (design-system leaves that should model for stability).
- `WarningBanner.kt:65-102` duplicates the whole `Surface { … }` block across an `onClick != null` / `onClick == null` branch to avoid an `onClick` param; a `Modifier.clickable` composition would remove the duplication.
- `RipDpiScaffolds.kt` has no `@Preview` annotations — the one reusable-component file missing preview coverage.

**Evidence**

- `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiSwitch.kt:149` — bare `"On"/"Off"` string · References: <https://developer.android.com/develop/ui/compose/resources>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiDropdown.kt:55,206,246` — `options: List<…>` · References: <https://developer.android.com/develop/ui/compose/performance/stability>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/LogRow.kt:37` — `metadataChips: List<String>` · References: <https://developer.android.com/develop/ui/compose/performance/stability>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/components/indicators/StatusIndicator.kt:119-154` — canvas dot sizes as `.dp` literals · References: <https://developer.android.com/develop/ui/compose/designsystems/material3>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/components/inputs/RipDpiTextField.kt:88,196`, `RipDpiDropdown.kt:89`, `RipDpiChip.kt:75,80,159`, `RipDpiBottomSheet.kt:173`, `RipDpiButton.kt:104,173` — mixed `.dp` literals · References: <https://developer.android.com/develop/ui/compose/designsystems/material3>
- `app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/WarningBanner.kt:65-102` — duplicated `Surface` branches · References: <https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md>
- Positive: all 16 shared components have correctly-placed `modifier` and slot conventions — see evidence in audit notes · References: <https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md>

## Prioritized Fixes

1. **Promote `List<T>` → `ImmutableList<T>` on the Diagnostics / chart / analysis hot-path composables (Critical 1)**. Target first: `DiagnosticsCards.kt`, `DiagnosticsCharts.kt`, `DiagnosticsLiveSection.kt`, `AnalysisProgressIndicator.kt` (`MainViewModel.AnalysisProgressUiState.stages`), then the two design-system leaves (`RipDpiDropdown`, `LogRow`). High leverage because it raises `skippable%` on the two screens with the most sustained rebuild pressure. References: <https://developer.android.com/develop/ui/compose/performance/stability/fix>.
2. **Materialise `filteredLogs` in `LogsViewModel` (Critical 2)**. Move filtering into the `combine` pipeline, expose `ImmutableList<LogEntry>`; removes a triple-allocation path inside `LogsScreen`. Also add `contentType = { "log_entry" }` to `LogsScreen.kt:464` to close the open compose-performance quick-win. References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>, <https://developer.android.com/develop/ui/compose/lists>.
3. **Replace effect `Channel.BUFFERED` + `receiveAsFlow()` with a buffered `SharedFlow` (Critical 3)**. Six ViewModels share the pattern; pick one (`DiagnosticsViewModel`) and build a small helper. Restores predictable event semantics across lifecycle transitions. References: <https://developer.android.com/develop/ui/compose/architecture>.
4. *(Follow-up)* Move navigation to type-safe `@Serializable` routes (Critical 4) and add a `components.indicatorDot*` / `components.chipIconSize` / `spacing.xxs` to the theme tokens so `StatusIndicator`, `RipDpiChip`, and `RipDpiTextField` stop using raw `.dp` (Critical 5). Also replace `"On"/"Off"` in `RipDpiSwitch` with a `stringResource`.

## Known Open Items

Cross-referenced against `.github/skills/compose-performance/SKILL.md` quick-wins. These are tracked debt, not new findings:

- Add `contentType` to `LogsScreen` `itemsIndexed` — still open (`LogsScreen.kt:464`).
- Remove index prefix from `DiagnosticsScanSection` probe key — still open (noted in the SKILL.md; the scheme `"$index-${probe.target}-${probe.outcome}"` persists).
- Run compiler reports and diff `-composables.csv` against baseline — not completed for this audit run (see Notes And Limits).
- Add `TrackRecomposition` to `LogsScreen` and `AdvancedSettingsScreen` — still open. (`HomeScreen` and `DiagnosticsScreen` are already instrumented.)

Items the audit confirms are complete: `@Immutable`/`@Stable` coverage on UI model classes, `compose-stability.conf` entries, `key` on every LazyColumn items, hoisted `remember`-ed lambdas (see HomeScreen lambda remember pattern at HomeScreen.kt:183).

## Notes And Limits

- **Scope**: `:app` is the only Compose module in the workspace. `:core:data`, `:core:diagnostics`, `:core:engine`, `:core:service`, `:core:detection` contain zero `@Composable` definitions — they were excluded and do not affect this score.
- **Confidence: Medium**. The run did not produce verified `compose-reports/` / `compose-metrics/` output. A cached build made the `-Pripdpi.composeReports=true` flag no-op; a `--rerun-tasks` follow-up was still executing at report time. Stability claims in Performance are source-inferred — plainly labelled as such. Per the skill rubric (`references/scoring.md` — "Step 4 failed"), the qualitative Performance score is capped at 7 before weighting.
- **Weight choice**: default 35/25/20/20. Not renormalised — no category was `N/A`.
- **Renormalization**: none applied.
- **Compiler diagnostics used**: no — re-run `./gradlew :app:compileReleaseKotlin -Pripdpi.composeReports=true --rerun-tasks` and read `app/build/compose-reports/` (named-only `skippable% = sum(skippable) / sum(restartable)` filtered to `isLambda == 0` in `*-composables.csv`, cross-checked against `*-module.json`) to obtain a measured ceiling. Given the extensive `@Immutable`/`@Stable` discipline, a measured `skippable%` ≥ 85% with ≤3 shared unstable types would lift Performance to the 8 cap; the `List<T>` findings above suggest the number will land closer to 70–85% (ceiling 6), which does not change the applied score.
- **Design scoring**: not in v1 scope. The report does not grade `RipDpiThemeTokens` vs Material 3 tokens. See Suggested Follow-Up.

## Suggested Follow-Up

- **Yes, run the `material-3` skill**. This audit surfaces a rich custom wrapper (`RipDpiThemeTokens` layered on top of `MaterialTheme`). A Material-3-specific audit would evaluate whether the token layer faithfully maps onto `colorScheme`/`typography`/`shapeScheme` and whether dark-mode / dynamic-color paths are complete. The hardcoded `dp` values in Critical 5 also hint at gaps in the spacing/indicator scale that the design-system audit can formalise.
- Optionally re-run this Compose audit after fixes 1–3 land to lock in a measured `skippable%` baseline and promote confidence to High.
