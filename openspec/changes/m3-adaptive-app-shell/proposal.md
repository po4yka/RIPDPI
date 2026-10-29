# Change: Move the app shell to adaptive Material 3 navigation

Task ID: `UIX-1793256870879296`

## Why

The bottom bar and rail are custom, the rail reads LocalConfiguration.screenWidthDp while the theme reads the window container size, and the shell does not know the large and extra-large window classes.

## What Changes

- Add material3-adaptive and adaptive-navigation-suite dependencies.
- Replace BottomNavBar and RipDpiNavRail with NavigationSuiteScaffold.
- Read window size classes from currentWindowAdaptiveInfo with large and extra-large support.
- Move RipDpiTopAppBar internals to Material 3 top app bars with scroll behavior.
- Add predictive back transitions to Navigation Compose destinations.

## Capabilities

### New Capabilities

- `m3-adaptive-app-shell`: Move the app shell to adaptive Material 3 navigation.

### Modified Capabilities

- None.

## Impact

`:app` navigation, scaffolds, and layout tokens; `gradle/libs.versions.toml`. Navigation Compose stays. Screenshot fixtures for navigation chrome change.
