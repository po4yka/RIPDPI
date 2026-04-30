---
name: compose-performance
description: Compose performance, recomposition, stability reports, Lazy lists, annotations, and metrics.
---

# Compose Performance -- RIPDPI

## 1. Stability system

The Compose compiler decides at compile time whether each class is **stable**
(safe to skip recomposition when equal) or **unstable** (must always recompose).
Classes from non-Compose modules (protobuf, java.time, Room entities) are
unstable by default unless declared in the stability config.

**@Immutable** -- all public properties never change after construction. Use
for sealed-class hierarchies and pure value types (DiagnosticsUiModels,
HistoryUiModels, theme tokens like Color, Spacing, Shape, Surface, Motion).

**@Stable** -- property reads return equal values between recompositions, or
the runtime is notified on change. Use for state holders with callbacks or
MutableState (SettingsUiModels: DesyncCoreUiState, ProxyNetworkUiState, etc.).

### Project conventions

- `activities/DiagnosticsUiModels.kt` -- 60+ annotations, @Immutable for data,
  @Stable for models with callbacks.
- `activities/SettingsUiModels.kt` -- @Stable on all section state holders.
- `activities/HistoryUiModels.kt` -- @Immutable on all models (pure data).
- `activities/MainViewModel.kt` -- @Immutable on sealed states.
- `ui/theme/` -- @Immutable on all token classes.

**Rule**: every new class passed to a @Composable MUST be annotated. Explicit
annotation prevents regressions when a field type changes.

### Stability configuration file

`app/compose-stability.conf` marks external types as stable:
- `com.poyka.ripdpi.proto.*`, `com.google.protobuf.GeneratedMessageLite`
- `java.time.{Instant,Duration,LocalDate,LocalDateTime,ZonedDateTime,ZoneId}`
- `kotlin.time.Duration`, `kotlinx.collections.immutable.*`
- `com.poyka.ripdpi.data.{TcpChainStepModel,UdpChainStepModel,ActivationFilterModel,NumericRangeModel}`

Add new non-Compose-module types here rather than wrapping in UI models.

## 2. Compiler metrics

Convention plugin `ripdpi.android.compose.gradle.kts` enables reports when
`CI=true` or `-Pripdpi.composeReports=true`:

```bash
./gradlew :app:assembleRelease -Pripdpi.composeReports=true
```

Output: `app/build/compose-reports/` and `app/build/compose-metrics/`.

| File | Purpose |
|------|---------|
| `*-composables.txt` | Restartable/skippable status and param stability |
| `*-classes.txt` | Stability of every class seen by the compiler |
| `*-composables.csv` | Machine-readable, diff between commits |
| `*-module.json` | Module summary: skippable %, restartable count |

Look for "not skippable" composables (unstable parameter) and "unstable"
classes (often a `List<T>` that should be `ImmutableList<T>`).

## 3. Recomposition debugging

### TrackRecomposition (built-in, ui/debug/TrackRecomposition.kt)

- `TrackRecomposition(tag)` -- SideEffect counting recompositions. Already in
  HomeScreen. Add to any suspect composable.
- `RecompositionReportEffect(intervalMs)` -- periodic logcat dump. Place once
  in root composable. Markers: `!` (delta>5), `!!!` (delta>20).
- Filter: `adb logcat -s RecomposeTracker:D RecomposeReport:I *:S`
- No-op in release builds (gated on BuildConfig.DEBUG).

### Layout Inspector

Enable "Show Recomposition Counts" in the toolbar. High recomposition count
with low skip count = hot path that needs investigation.

### Compose runtime tracing

The project includes `androidx-compose-runtime-tracing`. Capture a Perfetto
trace and open in ui.perfetto.dev, filter for `Compose:` slices.

## 4. LazyColumn optimization

### Keys and contentType

Every `items()` call MUST provide `key`. Without keys, Compose recreates item
state on every list change. Also provide `contentType` when mixing different
item structures -- the slot pool is keyed by content type.

Project status:
- HistoryScreen: `key = { it.id }`, `contentType = { "connection_session" }`
- DiagnosticsLiveSection: `key = { it.label }`, `contentType = { "trend" }`
- LogsScreen: `key = { _, entry -> entry.id }` -- missing `contentType`

### Lambda captures

Avoid new lambda instances inside `items {}`. Hoist callbacks:

```kotlin
// Bad: new lambda per recompose
items(list, key = { it.id }) { item ->
    Row(onClick = { viewModel.onClick(item.id) })
}
// Good: stable outer lambda
val onClick = remember(viewModel) { { id: String -> viewModel.onClick(id) } }
items(list, key = { it.id }) { item ->
    Row(onClick = { onClick(item.id) })
}
```

### Avoid index-based keys

DiagnosticsScanSection uses `"$index-${probe.target}-${probe.outcome}"`. The
index prefix defeats stable keys during insertions/deletions. Prefer a
domain-unique identifier.

## 5. Project-specific screen performance

### HomeScreen
- `rememberInfiniteTransition` for connecting-state pulse -- correctly scoped
  to `ConnectionState.Connecting`, stops when idle.
- `animateColorAsState`, `animateFloatAsState` for press feedback.
- `AnimatedContent` for state transitions.
- Already instrumented with `TrackRecomposition("HomeScreen")`.
- Watch: entire screen recomposing on connection state change. MainUiState is
  @Immutable, so parameters should remain stable.

### DiagnosticsScreen (~1200 lines)
- HorizontalPager with multiple tabs, each containing a LazyColumn.
- `derivedStateOf` for event filtering -- correct pattern.
- LazyRow chips with proper keys and contentType.
- Watch: off-screen page recomposition. HorizontalPager defaults to
  `beyondBoundsPageCount = 0` -- do not increase it.

### LogsScreen
- Streaming viewer, potentially thousands of entries.
- `derivedStateOf` for FAB visibility based on scroll position.
- LazyColumn has `key` but no `contentType` (fix opportunity).
- ViewModel converts to `ImmutableList<LogEntry>` before UI exposure.
- Watch: `entries.size` in header causing parent recomposition on each new
  log line. Int is stable, but keep parent scope narrow.

### AdvancedSettingsScreen
- RipDpiSettingsScaffold wrapping LazyColumn, ~20 expandable sections.
- Each section is `item(key = "...")`, all state holders are @Stable.
- Architecture passes individual section state (DesyncCoreUiState, etc.)
  so each section only recomposes when its own state changes.

### HistoryScreen
- Three tabs, each with LazyColumn. All items have `key` + `contentType`.
- Models are @Immutable. Uses `derivedStateOf` for empty-state detection.

## 6. Quick wins checklist

- [ ] Add `contentType` to LogsScreen LazyColumn `itemsIndexed` call
- [ ] Remove index prefix from DiagnosticsScanSection probe key
- [ ] Run compiler reports and diff `-composables.csv` against baseline
- [ ] Add `TrackRecomposition` to DiagnosticsScreen, LogsScreen,
      AdvancedSettingsScreen
- [ ] Verify new UI model classes have @Immutable or @Stable
- [ ] New compose-stability.conf entries must match actual immutability
- [ ] New LazyColumn: always provide `key` and `contentType`
- [ ] Hoist and `remember` lambdas passed to LazyColumn items
- [ ] Use `ImmutableList` for list params to @Composable, not `List<T>`
