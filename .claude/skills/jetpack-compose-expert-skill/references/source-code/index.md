# Compose source code receipts

Source code for the Compose libraries lives upstream. When you need to verify
how something works internally — or the user asks "show me the actual
implementation" — fetch the file directly with WebFetch or `gh api`.

URL templates:

- androidx (branch `androidx-main`):
  `https://raw.githubusercontent.com/androidx/androidx/androidx-main/<path>`
- compose-multiplatform-core (branch `jb-main`):
  `https://raw.githubusercontent.com/JetBrains/compose-multiplatform-core/jb-main/<path>`

Replace `<path>` with the relative file path from the tables below.

## Runtime — `androidx-main`

Path prefix: `compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/`

| File | Purpose |
|------|---------|
| `Composable.kt` | The `@Composable` annotation |
| `Composition.kt` | `Composition`, `ControlledComposition` |
| `Composer.kt` | The compiler-driven `Composer` runtime entry point |
| `Recomposer.kt` | Schedules recomposition |
| `State.kt`, `SnapshotState.kt` | `State`, `MutableState`, `mutableStateOf` |
| `snapshots/Snapshot.kt` | Snapshot state system |
| `Effects.kt` | `LaunchedEffect`, `DisposableEffect`, `SideEffect` |
| `CompositionLocal.kt` | `staticCompositionLocalOf`, `compositionLocalOf` |
| `Remember.kt` | `remember` and `rememberSaveable` |
| `SlotTable.kt` | The internal slot table that tracks composition |

## UI — `androidx-main`

Path prefixes:
- common: `compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/`
- android: `compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/`

| File | Purpose |
|------|---------|
| `Modifier.kt` (common) | Modifier chain root |
| `node/ModifierNodeElement.kt` | Modifier.Node API |
| `layout/Layout.kt` | The `Layout` composable |
| `node/LayoutNode.kt` | Internal layout tree node |
| `draw/DrawModifier.kt` | Draw-phase modifiers |
| `platform/AndroidCompositionLocals.android.kt` | `LocalContext`, `LocalDensity` |

## Foundation — `androidx-main`

Path prefix: `compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/`

| File | Purpose |
|------|---------|
| `lazy/LazyList.kt` | `LazyColumn`/`LazyRow` impl |
| `lazy/grid/LazyGrid.kt` | `LazyVerticalGrid` impl |
| `text/BasicTextField.kt` | Core text field |
| `Clickable.kt` | `Modifier.clickable` |
| `gestures/Scrollable.kt` | `Modifier.scrollable` |
| `pager/Pager.kt` | `HorizontalPager`/`VerticalPager` |

## Material3 — `androidx-main`

Path prefix: `compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/`

| File | Purpose |
|------|---------|
| `MaterialTheme.kt` | `MaterialTheme` composable |
| `ColorScheme.kt` | Color tokens |
| `Button.kt` | Button variants |
| `Scaffold.kt` | App scaffold |
| `TextField.kt` | Material text field |
| `NavigationBar.kt` | Bottom navigation bar |

## Navigation — `androidx-main`

Path prefix: `compose/navigation/navigation-compose/src/commonMain/kotlin/androidx/navigation/compose/`

| File | Purpose |
|------|---------|
| `NavHost.kt` | `NavHost` composable |
| `ComposeNavigator.kt` | Navigator implementation |
| `NavGraphBuilder.kt` | DSL for `composable {}` destinations |
| `DialogNavigator.kt` | Dialog destination navigator |

## CMP — `jb-main`

Path prefixes vary; the source map is:

| File | Path |
|------|------|
| Desktop `Window.kt` | `compose/ui/ui/src/desktopMain/kotlin/androidx/compose/ui/window/Window.desktop.kt` |
| iOS `ComposeUIViewController.kt` | `compose/ui/ui/src/uikitMain/kotlin/androidx/compose/ui/window/ComposeUIViewController.uikit.kt` |
| iOS `UIKitView.kt` | `compose/ui/ui/src/uikitMain/kotlin/androidx/compose/ui/interop/UIKitView.uikit.kt` |
| Web `ComposeViewport.kt` | `compose/ui/ui/src/webMain/kotlin/androidx/compose/ui/window/ComposeViewport.web.kt` |
| Resources `ResourceReader.kt` | `components/resources/library/src/commonMain/kotlin/org/jetbrains/compose/resources/ResourceReader.kt` (in the separate `JetBrains/compose-multiplatform` repo, branch `master`) |
