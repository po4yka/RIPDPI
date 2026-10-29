# Change: Epic - Material 3 UI and UX overhaul

Task ID: `EPC-1793256695505524`

## Why

The app uses only part of Material 3. Contrast levels exist only in previews, Material-hosted components and RDS components use different motion, navigation chrome is custom, the icon library is deprecated, and large windows show one stretched layout.

## What Changes

- Coordinate four child changes: `m3-theme-foundation`, `m3-adaptive-app-shell`, `m3-component-facade`, and `m3-screen-layouts`.
- Define the epic-level acceptance contract. Each child change owns its own behavior deltas, design, and evidence.

## Capabilities

### New Capabilities

- `material3-ui-overhaul`: the epic completion contract for the Material 3 UI and UX overhaul.

### Modified Capabilities

- None.

## Impact

- Portfolio area: `epic`. Child changes affect `:app` UI, design documentation, project skills, screenshot fixtures, and dependencies. Native, JNI, and network behavior do not change.
