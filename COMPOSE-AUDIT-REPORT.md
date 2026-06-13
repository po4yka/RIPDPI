# Jetpack Compose Audit Report

Target: /Users/po4yka/GitRep/RIPDPI (`:app`)
Date: 2026-06-12
Scope: `app/src/main/kotlin/com/poyka/ripdpi/` (the only Compose module)
Excluded from scoring: `@Preview` composable bodies, `src/test/`, `baselineprofile/`
Confidence: High
Overall Score: 82/100
Design system: RipDpiThemeTokens (custom wrapper over MaterialTheme) -- see `.claude/skills/material-3/` for design scoring
Companion report: `UI-UX-CORE-CONNECTION-AUDIT.md` (UX + core↔UI connection findings, outside this rubric)

## Scorecard

| Category | Score | Weight | Status | Notes |
|----------|-------|--------|--------|-------|
| Performance | 8/10 | 35% | solid | Named-only skippable = 100%; a handful of unmemoized transforms and one tracked lazy-key bug remain |
| State management | 8/10 | 25% | solid | 100% lifecycle-aware collection, buffered SharedFlow effects; isolated saveable/draft gaps |
| Side effects | 9/10 | 20% | excellent | Deliberate, lifecycle-aware effect usage throughout; only clarity-level findings |
| Composable API quality | 8/10 | 20% | solid | Systemically clean component APIs; orphaned components and a few token-literal leaks |

Performance ceiling check (measured):
```
overall skippable% = 2015/2811 = 71.7% (anchored by structurally non-skippable lambdas)
named-only skippable% = 1144/1144 = 100% (app-composables.csv, isLambda == 0)
unstable classes used as shared/reusable component params = 0
  (unstable params — DetectionCheckResult, WidgetSnapshot, VerdictNarrative, ConfigUiState, etc. —
   appear only on screen-level composables, all of which remain skippable under Strong Skipping;
   the ui/components/ API surface takes plain value + callback only)
-> ceiling row "skippable% >= 95% and zero unstable shared params" -> no cap
qualitative score: 8; applied score: 8 (no ceiling lowering)
```
Feature flags from `app-module.json`: StrongSkipping=true, IntrinsicRemember=true, OptimizeNonSkippingGroups=true, PausableComposition=true.

## Critical Findings

1. **Performance: index-prefixed lazy key + unmemoized `.reversed().take()` in the scan probe list (tracked, still open)**
   - Why it matters: the `$index-` prefix re-keys every row when a probe is prepended, defeating item identity and recomposing the whole visible list during an active scan — exactly when the screen is busiest. The `.reversed().take(N)` allocates on every recomposition.
   - Evidence: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScanSection.kt:213-214`
   - Fix direction: drop the index from the key (use the probe's stable identity) and wrap the list transform in `remember(progress.completedProbes)`.
   - References: <https://developer.android.com/develop/ui/compose/lists>, <https://developer.android.com/develop/ui/compose/performance/bestpractices>

2. **State: `rememberSaveable` draft initialized from ViewModel state with no key — silent stale editor**
   - Why it matters: `configText by rememberSaveable { mutableStateOf(uiState.desync.chainDsl) }` captures the chain DSL once. If the DSL changes elsewhere (preset applied, another screen saves), the strategy editor silently shows stale content the user may then re-save, overwriting the newer config.
   - Evidence: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/StrategyConfigRoute.kt:48`
   - Fix direction: key the saveable on the upstream value (resets draft on external change, matching the `DnsSettingsInputState.kt:67-106` convention used elsewhere) or sync external updates via an effect.
   - References: <https://developer.android.com/develop/ui/compose/state>

3. **State: one-shot effects modeled as `MutableStateFlow<Effect?>` in BackupRestoreViewModel**
   - Why it matters: four effect channels use nullable `StateFlow` + manual null-reset instead of the project's own `MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)` convention. Events can be missed around Activity recreation, and the pattern invites re-delivery bugs.
   - Evidence: `app/src/main/kotlin/com/poyka/ripdpi/backup/BackupRestoreViewModel.kt:155-165` (consumed at `BackupRestoreScreen.kt:82-83,169,279`)
   - Fix direction: migrate to the buffered `SharedFlow` shape used by `MainViewModel`/`ConfigViewModel`/`DiagnosticsViewModel`.
   - References: <https://developer.android.com/develop/ui/compose/architecture>

4. **API quality: orphaned shared components shipping unlocalized UI strings**
   - Why it matters: `RipDpiCommandPalette` and `CensorshipSignatureScreen` are public composables with zero call sites, and both contain hardcoded English (`"Type a command…"`, `"No matching command"`, `"Censorship signatures"`) in an 8-locale app. Dead UI accrues i18n and maintenance debt invisibly because the `MissingTranslation` gate only sees `strings.xml` keys.
   - Evidence: `app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiCommandPalette.kt:117,148`; `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/CensorshipSignatureScreen.kt:61,111`
   - Fix direction: delete or wire up both surfaces; if kept, move strings to resources in all 8 locales.
   - References: <https://developer.android.com/develop/ui/compose/resources>

5. **Performance: repeated pattern of collection transforms in composable bodies without `remember`**
   - Why it matters: each occurrence allocates per recomposition; individually small, but the pattern recurs across diagnostics cards and profile forms.
   - Evidence: `ui/screens/diagnostics/DiagnosticsCards.kt:114,141`; `ui/screens/diagnostics/DiagnosticsTelegramCards.kt:31,74`; `ui/screens/mieru/MieruProfileScreen.kt:134,143`; `ui/screens/ssh/SshProfileScreen.kt:133`; `ui/screens/detection/DetectionSettingsScreen.kt:396-401`
   - Fix direction: `remember(input) { transform }`; for the static top-level option lists (Mieru/Ssh/Detection), hoist the mapped list to a module-level `val`.
   - References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>

## Category Details

### Performance — 8/10

**What is working**

- Measured: named-only skippable% = 100% (1144/1144); only 308 of 42,217 arguments known-unstable. The explicit `@Immutable`/`@Stable` discipline plus `app/compose-stability.conf` demonstrably pays off.
- `kotlinx.collections.immutable` used at ~391 sites; no raw `List` params on shared components.
- Lazy lists carry `key` + `contentType` across `DiagnosticsLiveSection`, `HistorySections`, `PcapViewerScreen`, `RipDpiLogStream`, and the previously-open LogsScreen gap is now fixed (`LogsScreen.kt:493`).
- Typed state factories (`mutableIntStateOf`/`mutableFloatStateOf`/`mutableLongStateOf`) used consistently; zero boxed primitive state found.
- `derivedStateOf` used correctly for scroll-threshold booleans (`LogsScreen.kt:205-213`, `HistorySections.kt:215-219`).
- Baseline profile pipeline present: `libs.androidx.profileinstaller` (`app/build.gradle.kts:274`) + `app/src/main/baseline-prof.txt`.
- `TrackRecomposition` instrumentation actively used; zero Accompanist, zero `Modifier.composed`, zero `animateItemPlacement`.

**What is hurting the score**

- The tracked index-prefixed lazy key remains unfixed (Critical Finding 1).
- Repeated unmemoized collection transforms (Critical Finding 5).
- `SharingStarted.Eagerly` in `HomeDiagnosticsStateOwner` without a justifying comment, diverging from the project-wide `WhileSubscribed(5_000)` convention.

**Evidence**

- `ui/screens/diagnostics/DiagnosticsScanSection.kt:213-214` — index-prefixed key + unmemoized transform · References: <https://developer.android.com/develop/ui/compose/lists>
- `ui/screens/diagnostics/DiagnosticsCards.kt:114,141` — filter+map per recomposition · References: <https://developer.android.com/develop/ui/compose/performance/bestpractices>
- `activities/HomeDiagnosticsStateOwner.kt:48,61` — `Eagerly` keeps upstream collection alive while backgrounded, undocumented · References: <https://developer.android.com/develop/ui/compose/architecture>
- `ui/screens/logs/LogsScreen.kt:147-183` — bounded `LazyColumn` inside `verticalScroll` Column; acceptable due to `heightIn(max = 420.dp)` but limits recycling · References: <https://developer.android.com/develop/ui/compose/lists>

### State Management — 8/10

**What is working**

- 0 plain `collectAsState()`; 50+ `collectAsStateWithLifecycle()` — full lifecycle-aware adoption.
- `WhileSubscribed(5_000)` consistently across all ViewModels.
- One-shot effects via `MutableSharedFlow(extraBufferCapacity = 1, DROP_OLDEST)` in 6 ViewModels.
- Fully type-safe `@Serializable` navigation (`ui/navigation/Route.kt`), no string routes.
- Stateful-route / stateless-screen split at every entry point; ViewModel access only at route level.
- `rememberSaveable` keyed on upstream data for field resets (`DnsSettingsInputState.kt:67-106`).

**What is hurting the score**

- Stale-draft saveable in StrategyConfigRoute (Critical Finding 2).
- Nullable-StateFlow effects in BackupRestoreViewModel (Critical Finding 3).
- Scattered minor saveability gaps: Diagnostics pager (`remember`-only, visible page-0 flash on rotation), nav-host deep-link section state, Logs scroll position.
- `remember {}` caching a side-effecting system read with no refresh: `SettingsPreferencesScreen.kt:62-63` (`isBatteryOptimizationIgnored()` — stale until recomposition from scratch).

**Evidence**

- `ui/screens/settings/StrategyConfigRoute.kt:48` — keyless saveable captures ViewModel state · References: <https://developer.android.com/develop/ui/compose/state>
- `backup/BackupRestoreViewModel.kt:155-165` — `StateFlow<Effect?>` one-shot events · References: <https://developer.android.com/develop/ui/compose/architecture>
- `ui/screens/diagnostics/DiagnosticsRoute.kt:56,85-87` — pager not saveable; post-hoc sync from ViewModel · References: <https://developer.android.com/develop/ui/compose/state>
- `ui/screens/settings/SettingsPreferencesScreen.kt:62-63` — keyless `remember` around system API read · References: <https://developer.android.com/develop/ui/compose/state>
- `ui/screens/detection/DetectionResultCards.kt:467` — `rememberSaveable` of `Set<String>` via implicit Java serialization; prefer `listSaver` · References: <https://developer.android.com/develop/ui/compose/state>

### Side Effects — 9/10

**What is working**

- Navigation exclusively inside `LaunchedEffect` bodies (`RipDpiNavHost.kt:278-329`).
- `rememberUpdatedState` correctly applied at the three long-lived-effect callback sites (`ModeEditorRoute.kt:139`, `DiagnosticsRoute.kt:106`, `OnboardingScreen.kt:166-168`).
- `DisposableEffect` cleanup verified (e.g. `FLAG_SECURE` add/remove pairs in `StrategyConfigRoute.kt:198-201`, `BiometricPromptScreen.kt:155-158`).
- All `rememberCoroutineScope()` usage is event-driven; no lifecycle reinvention.
- No repository calls, thread launches, or `GlobalScope` in composition anywhere.

**What is hurting the score**

- `ReplayFailureRoute.kt:25-36` — `LaunchedEffect(Unit)` reads live snapshot `state` in its body as a guard; should key on the condition or wrap in `rememberUpdatedState`.
- `LaunchedEffect(viewModel)` as an opaque run-once idiom in 5 routes — functionally fine, unclear intent for readers.

**Evidence**

- `ui/screens/diagnostics/ReplayFailureRoute.kt:25-36` — live state read in `LaunchedEffect(Unit)` body · References: <https://developer.android.com/develop/ui/compose/side-effects>
- `ui/screens/settings/AdvancedSettingsRoute.kt:92`, `ModeEditorRoute.kt:159`, `HistoryScreen.kt:48`, `DiagnosticsRoute.kt:50,109` — `LaunchedEffect(viewModel)` clarity issue · References: <https://developer.android.com/develop/ui/compose/side-effects>

### Composable API Quality — 8/10

**What is working**

- `modifier: Modifier = Modifier` as first optional param, applied once to the root node — systemic across the ~60-component library.
- Zero `MutableState<T>`/`State<T>` parameters in reusable APIs; plain value + callback everywhere.
- `staticCompositionLocalOf` used for all theme tokens; no component-config CompositionLocals.
- `Scaffold` `innerPadding` correctly propagated through every scaffold layer.
- Light+dark `@Preview` pairs on nearly all component files.

**What is hurting the score**

- Orphaned components with unlocalized strings (Critical Finding 4).
- Raw layout literals outside the token system: `StageProgressIndicator.kt:92,104` (`4.dp`, `6.dp`), `RipDpiAccordion.kt:84` (`20.dp`) — violates the project's own RDS token rule.
- Preview gaps on the most load-bearing chrome: `RipDpiScreenChrome.kt` (5 public composables, zero previews) and `RipDpiConnectionActuator.kt` (the main connect control, no per-state previews).
- `LanguagePickerSheet.kt:26` lacks a `modifier` param; `RipDpiCommandPalette` forwards `modifier` to an inner `Column` instead of the root.

**Evidence**

- `ui/components/feedback/RipDpiCommandPalette.kt:117,148` — hardcoded strings in shared component · References: <https://developer.android.com/develop/ui/compose/resources>
- `ui/components/indicators/StageProgressIndicator.kt:92,104`; `ui/components/feedback/RipDpiAccordion.kt:84` — dp literals outside token system · References: <https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md>
- `ui/components/chrome/RipDpiScreenChrome.kt` — zero `@Preview` on 5 public chrome composables · References: <https://developer.android.com/develop/ui/compose/tooling/previews>
- `ui/components/LanguagePickerSheet.kt:26` — missing `modifier` param · References: <https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/docs/compose-component-api-guidelines.md>

## Prioritized Fixes

1. `DiagnosticsScanSection.kt:213-214` — remove the `$index-` prefix from the lazy key and `remember(progress.completedProbes)` the `.reversed().take()` transform. Closes the last open compiler-adjacent perf bug during active scans. (<https://developer.android.com/develop/ui/compose/lists>)
2. `StrategyConfigRoute.kt:48` — key the `rememberSaveable` draft on `uiState.desync.chainDsl` (or sync via effect) to eliminate the stale-editor overwrite risk. (<https://developer.android.com/develop/ui/compose/state>)
3. `BackupRestoreViewModel.kt:155-165` — replace the four nullable-`StateFlow` effect channels with the project-standard buffered `SharedFlow`. (<https://developer.android.com/develop/ui/compose/architecture>)
4. Follow-up: delete or wire `RipDpiCommandPalette` + `CensorshipSignatureScreen`; move their strings (and the clipboard labels catalogued in `UI-UX-CORE-CONNECTION-AUDIT.md` §5) into resources across all 8 locales.

## Known Open Items

Cross-referenced against `.github/skills/compose-performance/SKILL.md` quick-wins checklist:

- LogsScreen `contentType` — **FIXED** (`LogsScreen.kt:493` now passes `contentType`).
- DiagnosticsScanSection index-prefixed probe key — **STILL OPEN** (Prioritized Fix 1).
- `@Immutable`/`@Stable` on new UI models + `compose-stability.conf` coverage — **HOLDING** (measured 100% named skippability), with the noted exception that `ConfigUiState` is runtime-unstable via `RelayTrustDomainWarning?`; harmless under Strong Skipping but worth annotating for convention consistency.
- `TrackRecomposition` on DiagnosticsScreen/LogsScreen/AdvancedSettingsScreen — partially adopted (present on scan/live sections).

## Notes And Limits

- Only `:app` audited (the sole Compose module per project orientation).
- Weight choice: default 35/25/20/20.
- Renormalization: none (no N/A categories).
- Compiler diagnostics used: **yes** — `app/build/compose-reports/app-composables.csv`, `app-classes.txt`, and `app/build/compose-metrics/githubRelease/app-module.json` from `./gradlew :app:assembleRelease -Pripdpi.composeReports=true` (2026-06-12). Named-only skippable% computed by filtering `isLambda == 0`.
- The 71.7% overall skippable figure is reported for completeness; it is anchored by zero-argument lambdas that structurally cannot skip and is not used for the ceiling.
- UX, localization, accessibility, and core↔UI connection findings are out of this rubric's scope and live in `UI-UX-CORE-CONNECTION-AUDIT.md`.

## Suggested Follow-Up

- A `material-3` audit is not urgent: token discipline is test-enforced and the component library is consistent. Worth running only if the RDS coverage backlog (`docs/design/rds/COVERAGE.md`) work begins.
