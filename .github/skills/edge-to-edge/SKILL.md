---
name: edge-to-edge
description: Edge-to-edge and window-inset handling for Compose screens, scaffolds, sheets, and system bars.
---

# Edge-to-Edge on RIPDPI

RIPDPI targets `targetSdk = 35` on `compileSdk = 36`. On Android 15+ (API 35+) the system **enforces** edge-to-edge regardless of theme flags — any screen that wraps content without inset handling will clip under the status / navigation bars.

## The single entry point

`app/src/main/kotlin/com/poyka/ripdpi/activities/MainActivity.kt:74-76` is the only call site:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    val splashScreen = installSplashScreen()
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    ...
}
```

**Rule:** `installSplashScreen()` must run before `enableEdgeToEdge()`, and both must run before `super.onCreate(...)`. The `androidx.core.splashscreen:1.2.0` (pinned in `gradle/libs.versions.toml:33`) contract assumes the splash overlay lifecycle is attached before the activity's window decoration switches to edge-to-edge mode; reversing the order produces a one-frame flash of the default system background at cold launch.

Do **not** call `enableEdgeToEdge()` from a second activity, a Compose entry, or a `@Composable` effect. The activity owns the window.

## Choosing the right inset source in Compose

For any composable that touches a screen edge, you must pick **one** inset source:

| Inset | When to use | Notes |
|---|---|---|
| `WindowInsets.safeDrawing` | Default for most content surfaces. Union of system bars + display cutout + IME. | What you want unless you have a specific reason otherwise. |
| `WindowInsets.systemBars` | Only the status + navigation bars. Use when you explicitly want to draw under a display cutout. | Rare in RIPDPI; status bar is the common case. |
| `WindowInsets.navigationBars` | Bottom nav only. Use for a custom bottom bar that should sit above the system nav gesture area. | See `BottomNavBar.kt` for the anchor. |
| `WindowInsets.statusBars` | Top system bar only. Rare — prefer `safeDrawing` so cutouts are handled. | Use when you want to draw behind the status bar but respect the nav bar normally. |
| `WindowInsets.ime` | Keyboard insets. | Combine with others via `union()` for fields that must stay above the keyboard. |

## `windowInsetsPadding` vs `safeDrawingPadding`

```kotlin
Modifier.windowInsetsPadding(WindowInsets.navigationBars)   // only nav bar
Modifier.safeDrawingPadding()                               // safeDrawing shortcut
```

`safeDrawingPadding()` is the convenience modifier for `windowInsetsPadding(WindowInsets.safeDrawing)`. Prefer it on root containers. Use `windowInsetsPadding(...)` with an explicit inset source only when you need a specific subset (e.g. a bottom bar that handles just the nav bar, so the status bar is handled by the content above it).

**Do not stack padding modifiers** that apply overlapping insets — `safeDrawingPadding()` + `windowInsetsPadding(WindowInsets.statusBars)` double-pads the top. One modifier per axis, and let the composable tree handle the rest via `consumeWindowInsets`.

## Bottom sheets, dialogs, and the nav bar

`RipDpiBottomSheet.kt` and `BottomNavBar.kt` are the two bottom-edge anchor files. When authoring a new bottom sheet or modifying an existing one:

1. The sheet container itself is responsible for bottom inset padding. Its parent must **not** consume the nav bar inset first, or the sheet will be paddless.
2. Use `WindowInsets.navigationBarsIgnoringVisibility` for bottom sheets that must remain fixed even when the gesture nav pill transiently hides. This is the difference between a nav bar that briefly disappears during scroll and a sheet that jumps 24dp.
3. For the IME interaction: `WindowInsets.ime.union(WindowInsets.navigationBars)` on the sheet container ensures fields remain visible above the keyboard on devices where the IME doesn't fully overlap the nav bar.

## Scaffold caveats

Material 3 `Scaffold` in the Compose BOM `2026.03.01` (pinned in `libs.versions.toml:13`) handles insets for its own top bar, bottom bar, and FAB slots. If you put a custom bar outside a `Scaffold` slot and provide a `topBar`/`bottomBar` at the same time, you are inset-padding twice. Pick one:

- Use `Scaffold` slots and let it handle insets → don't add padding modifiers to the slot content.
- Don't use `Scaffold` slots and handle insets manually on your custom bars.

Mixing produces the classic "my bottom bar sits 48dp too high" regression.

## Splash screen handoff

`androidx.core.splashscreen:1.2.0` keeps the splash visible until `setKeepOnScreenCondition { ... }` returns false. `MainActivity.kt:92` wires this to `viewModel.startupState.value.isReady`. The splash overlay sits on top of the activity window, so edge-to-edge enforcement on `MainActivityContent` doesn't affect what the user sees during splash.

Gotcha: if you ever migrate away from `installSplashScreen()` to a custom splash composable, you must add inset handling to the custom composable yourself — it no longer has the splash library's overlay to hide behind.

## Verification

After any edge-to-edge change:

1. Visually verify on API 35 and API 30 emulators (Android 15 and Android 11). API 35 shows enforcement; API 30 shows the pre-enforcement behavior and confirms your rule doesn't break older OS versions.
2. Toggle gesture vs 3-button nav in the emulator (Settings → System → Gestures). Content must not clip under either nav style.
3. For any screen that takes keyboard input, open the IME and confirm the focused field stays visible. Run the existing Maestro flow `maestro/03-advanced-settings-edit-save.yaml` as a spot check for form-field insets.
4. Roborazzi screenshots at `app/src/test` must be re-recorded if inset padding on a visible surface changed: `./gradlew recordScreenshots` then review the diff.

## Out of scope

- Custom status-bar / nav-bar colors. The design system (`docs/design-system.md`) owns theming; edge-to-edge is about inset math, not color.
- Pre-API-30 behavior beyond the emulator spot check. `minSdk = 27` means the app runs on older OSes, but R-era inset APIs are the baseline; earlier system-ui-visibility flags are not used and must not be introduced.
- Window manager layout params (`FLAG_LAYOUT_NO_LIMITS`, `SOFT_INPUT_ADJUST_*`). These are legacy and must not be added; modern edge-to-edge uses the Compose inset system exclusively.

## External reference

Run `android skills edge-to-edge` for Google's generic edge-to-edge skill covering API surface background, cross-version inset compat, and the View-system equivalents. Use it alongside this RIPDPI-specific skill for context on *why* the API shape looks the way it does. This skill is the project-specific authority for *where* and *how* to apply insets in RIPDPI's Compose tree.
