# Change: Apply Material 3 canonical layouts to screens

Task ID: `UIX-1793256952721314`

## Why

Expanded and wider windows show one centered column, primary actions sit in screen content instead of Material placements, and long lists have no search.

## What Changes

- Add ListDetailPaneScaffold and SupportingPaneScaffold layouts to the main screen families.
- Move primary actions to FAB, app bar, or floating toolbar placements.
- Add search bars to Logs and History.
- Bring the remaining screen families onto the shared app bars and width rules.

## Capabilities

### New Capabilities

- `m3-screen-layouts`: Apply Material 3 canonical layouts to screens.

### Modified Capabilities

- None.

## Impact

`:app` screens, navigation routes for detail panes, UI tests, Appium page objects where locators move, and screenshot fixtures. New strings land in all ten locales.
