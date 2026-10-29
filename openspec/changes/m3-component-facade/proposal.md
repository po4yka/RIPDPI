# Change: Rebuild shared RipDpi components on Material 3

Task ID: `UIX-1793256927624290`

## Why

The RDS components rebuild state layers, surfaces, and styles that Material 3 already provides, through an experimental Foundation style API, which doubles maintenance and diverges from Material behavior.

## What Changes

- Rebuild action, input, container, feedback, and indicator components on Material 3 components.
- Delete RipDpiStateTokens, RipDpiSurfaceTokens, and the CommonStyle layer where Material 3 covers them, and their tests.
- Restyle custom product components with Material 3 tokens.
- Update the RDS component guard tests for the new rules.

## Capabilities

### New Capabilities

- `m3-component-facade`: Rebuild shared RipDpi components on Material 3.

### Modified Capabilities

- None.

## Impact

`:app` shared components and theme package, component tests, and screenshot fixtures. Screen files change only where a facade signature changes.
