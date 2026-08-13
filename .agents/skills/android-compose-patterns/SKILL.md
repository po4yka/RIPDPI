---
name: android-compose-patterns
description: Compose UI, ViewModels, Navigation 3, state, and app-module theming for RIPDPI. Use when implementing or reviewing Compose screens, typed navigation, lifecycle-aware state, or ViewModel wiring in the Android app.
---

# Android Compose Patterns

## Overview

RIPDPI uses Jetpack Compose with Material 3 and app-owned, typed Navigation 3 back stacks. State flows from DataStore through ViewModels to Composables via `StateFlow` + `collectAsStateWithLifecycle()`.

Diagnostics UI is a current hotspot in this repo. It layers internal UI models over shared diagnostics contracts, exposes stable automation tags through `RipDpiTestTags`, and routes callback-style actions such as opening Advanced Settings or candidate-detail sheets through screen-level parameters.

## Data Flow

```
DataStore (proto) -> ViewModel (StateFlow + combine) -> Composable (collectAsStateWithLifecycle)
User action -> ViewModel method -> DataStore.updateData { } or ServiceManager.start/stop
One-shot effects -> Channel<Effect> -> receiveAsFlow() -> LaunchedEffect collector
```

## Navigation

Treat these files as the navigation source of truth:

- `Route.kt`: serializable typed keys and external stable identifiers
- `RipDpiNavigationState.kt`: saveable gate and top-level back stacks plus entry decorators
- `RipDpiNavigator.kt`: the only app-owned stack mutation API
- `RipDpiNavHost.kt`: `entryProvider` builders and `NavDisplay`

Every route leaf is `@Serializable` and extends the sealed `Route : NavKey` hierarchy. `stableRoute` is a boundary identifier for automation, telemetry, and explicit external-launch parsing; never use it for in-app navigation.

### Adding a New Screen

1. Add an `@Serializable data object` or `data class` to `Route` with `stableRoute`, `@StringRes titleRes`, and optional `icon`. Keep arguments serializable and pass them as constructor properties.
2. Add the representative key to `Route.all`; add it to `Route.topLevel` only when it owns a retained top-level stack.
3. Register `entry<Route.YourRoute> { route -> ... }` in the appropriate `EntryProviderScope<NavKey>` builder in `RipDpiNavHost.kt`.
4. Pass typed `route` arguments to the Route composable or its explicitly constructed ViewModel. Do not decode them from a string route or Navigation 2 `SavedStateHandle.toRoute()`.
5. Navigate through a screen callback backed by `navigator.navigate(Route.YourRoute(...))`. Use `singleTop = true` only for actions that must be idempotent, and use `navigateTopLevel()` for an explicit tab switch.
6. Extend `Navigation3MigrationBoundaryTest`, `RipDpiNavHostLogicTest`, or `RipDpiNavigatorTest` for registry coverage, stack behavior, and lifecycle-sensitive changes. Update the explicit deep-link/launch parser when the route is externally reachable.

### Navigation Conventions

- Do not introduce `NavController`, `NavHost`, string destinations, or Navigation 2 `composable(...)` builders.
- Mutate stacks only through `RipDpiNavigator`; pass typed navigation callbacks from entries to Route/Screen composables.
- Keep onboarding and biometric routes in the separate gate stack; external requests must not bypass an active gate.
- Use `navigateTopLevel()` for the four retained top-level stacks. Restoration comes from `rememberNavBackStack`, not `launchSingleTop`/`restoreState` flags.
- Use `goBack()`, `resetToHome()`, and `replaceAll()` instead of mutating a back stack from a screen. Connect `NavDisplay.onBack` to `navigator.goBack()` so predictive and ordinary Back share the same mutation path.
- Keep route keys serializable and free of secrets; credential-bearing imports pass only opaque process-local tokens.
- Keep `rememberSaveableStateHolderNavEntryDecorator()` first, followed by `rememberRipDpiSharedViewModelStoreNavEntryDecorator()`. Acquire destination ViewModels inside the decorated entry/Route scope so they clear when popped.
- Use the existing metadata plus `sharedHiltViewModel()` path only for intentional Config/Settings feature scopes; never broaden destination ViewModels to the Activity to simulate sharing.
- Parse deep links and automation strings explicitly into typed `Route` values. Navigation 3 does not turn `stableRoute` strings into destinations automatically.

## ViewModel Pattern

```kotlin
class ExampleViewModel(application: Application) : AndroidViewModel(application) {
    // State: combine multiple sources into single UI state
    val uiState: StateFlow<UiState> = combine(
        application.settingsStore.data,
        _localState,
    ) { settings, local -> UiState(/*...*/) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    // Effects: one-shot events (navigation, snackbar, permission requests)
    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    // Actions: public methods called by Composables
    fun onAction() { viewModelScope.launch { /* ... */ } }
}
```

### Key Conventions

- `SharingStarted.WhileSubscribed(5_000)` for all StateFlow exports
- `AndroidViewModel` (not plain `ViewModel`) when DataStore access needed
- `Mutex` for thread-safe state transitions (see `MainViewModel.toggleService`)
- `Channel<Effect>` for one-shot UI events, collected via `LaunchedEffect`

## Composable Pattern

```kotlin
// Entry builder: resolves a typed key and owns navigation callbacks
private fun EntryProviderScope<NavKey>.addExampleRoute(navigator: RipDpiNavigator) {
    entry<Route.Example> { route ->
        ExampleRoute(
            itemId = route.itemId,
            onBack = navigator::goBack,
            onOpenDetails = { id -> navigator.navigate(Route.Details(id)) },
        )
    }
}

// Route composable: connects an entry-scoped ViewModel to pure UI
@Composable
fun ExampleRoute(
    itemId: Long,
    onBack: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    viewModel: ExampleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ExampleScreen(
        itemId = itemId,
        uiState = uiState,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenDetails = onOpenDetails,
    )
}

// Screen composable: pure UI, no ViewModel reference
@Composable
fun ExampleScreen(
    itemId: Long,
    uiState: UiState,
    onAction: () -> Unit,
    onBack: () -> Unit,
    onOpenDetails: (Long) -> Unit,
) { /* ... */ }
```

### Conventions

- Split Route (stateful) from Screen (stateless) composables
- Use `collectAsStateWithLifecycle()` (not `collectAsState()`)
- Pass callbacks, not ViewModel references, to Screen composables
- Use `RipDpiThemeTokens` for colors, spacing, typography

## Representative ViewModels

| ViewModel | Location | Purpose |
|-----------|----------|---------|
| `MainViewModel` | `activities/MainViewModel.kt` | Connection state, VPN/proxy toggle, metrics |
| `ConfigViewModel` | `activities/ConfigViewModel.kt` | Proxy config presets, draft editing, validation |
| `SettingsViewModel` | `activities/SettingsViewModel.kt` | App settings, theme, DataStore persistence |
| `DiagnosticsViewModel` | `activities/DiagnosticsViewModel.kt` | Diagnostics scan orchestration, history, export/share state, and strategy-probe presentation |

## Diagnostics UI Conventions

- Keep diagnostics projection logic in the `activities/DiagnosticsUi*` support files instead of recomputing report metadata directly in composables.
- Strategy-probe screens now have specialized presentation states such as candidate-aware progress, audit assessment, winners-first layout, and workflow restriction remediation.
- Prefer stable automation tags from `app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt` for any new externally exercised UI.
- Route navigation callbacks such as `onOpenAdvancedSettings`, `onSelectCandidate`, and sheet-dismiss actions through `Route`/`Screen` parameters rather than letting deep child composables navigate directly.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Navigating with `NavController` or `stableRoute` strings | Navigate with typed `Route` keys through `RipDpiNavigator` |
| Registering a destination with Navigation 2 `composable(...)` | Add an `entry<Route.Type>` to the Navigation 3 `entryProvider` |
| Assuming deep links are registered by a navigation graph | Extend the explicit external-launch parser and keep gate checks intact |
| Creating an Activity-scoped ViewModel for a destination | Create it inside a decorated `NavEntry`; use explicit shared-scope metadata only when required |
| Using `collectAsState()` | Use `collectAsStateWithLifecycle()` for lifecycle awareness |
| Passing ViewModel to Screen composable | Pass `uiState` and callbacks; keep Screen stateless |
| Hardcoding colors/spacing | Use `RipDpiThemeTokens` and Material 3 theme |
| Missing `WhileSubscribed(5_000)` | Required for proper lifecycle handling in all StateFlow exports |

## Related Skills

- **`jetpack-compose-api`** (`.github/skills/jetpack-compose-api/SKILL.md`):
  General Jetpack Compose API reference with guidance docs and actual androidx source code.
  Use for questions about how Compose APIs work internally, correct API usage patterns,
  recomposition mechanics, Modifier ordering, side-effects, performance, or accessibility.
