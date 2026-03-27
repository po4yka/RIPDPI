---
name: android-compose-patterns
description: Use when building or modifying Compose UI screens, ViewModels, navigation routes, state management, or theming in the app module
---

# Android Compose Patterns

## Overview

RIPDPI uses Jetpack Compose with Material 3 and a sealed-class navigation system. State flows from DataStore through ViewModels to Composables via `StateFlow` + `collectAsStateWithLifecycle()`.

Diagnostics UI is a current hotspot in this repo. It layers internal UI models over shared diagnostics contracts, exposes stable automation tags through `RipDpiTestTags`, and routes callback-style actions such as opening Advanced Settings or candidate-detail sheets through screen-level parameters.

## Data Flow

```
DataStore (proto) -> ViewModel (StateFlow + combine) -> Composable (collectAsStateWithLifecycle)
User action -> ViewModel method -> DataStore.updateData { } or ServiceManager.start/stop
One-shot effects -> Channel<Effect> -> receiveAsFlow() -> LaunchedEffect collector
```

## Navigation

Routes are defined as a sealed class in `app/.../ui/navigation/Route.kt`.

### Adding a New Screen

1. Add route to `Route` sealed class with `route` string, `@StringRes titleRes`, optional `icon`
2. If top-level: add to `Route.topLevel` list
3. Add to `Route.all` list
4. Add `composable(Route.YourRoute.route) { ... }` in `RipDpiNavHost.kt`
5. Navigate: `navController.navigate(Route.YourRoute.route) { launchSingleTop = true; restoreState = true }`

### Navigation Conventions

- Use `launchSingleTop = true` and `restoreState = true` for all navigations
- Bottom bar shows only for `isTopLevelDestination()` routes
- Pass ViewModels to route composables, not raw state

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
// Route composable: connects ViewModel to screen
@Composable
fun ExampleRoute(viewModel: ExampleViewModel, navController: NavController) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ExampleScreen(uiState = uiState, onAction = viewModel::onAction)
}

// Screen composable: pure UI, no ViewModel reference
@Composable
fun ExampleScreen(uiState: UiState, onAction: () -> Unit) { /* ... */ }
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
| Using `collectAsState()` | Use `collectAsStateWithLifecycle()` for lifecycle awareness |
| Passing ViewModel to Screen composable | Pass `uiState` and callbacks; keep Screen stateless |
| Hardcoding colors/spacing | Use `RipDpiThemeTokens` and Material 3 theme |
| Missing `WhileSubscribed(5_000)` | Required for proper lifecycle handling in all StateFlow exports |
| Creating ViewModel in Composable | Use `viewModel()` in Route composable or pass from NavHost |

## Related Skills

- **`jetpack-compose-api`** (`.github/skills/jetpack-compose-api/SKILL.md`):
  General Jetpack Compose API reference with guidance docs and actual androidx source code.
  Use for questions about how Compose APIs work internally, correct API usage patterns,
  recomposition mechanics, Modifier ordering, side-effects, performance, or accessibility.
