# Change: Reduce Android startup latency

Task ID: `AND-1795494902099648`

## Why

The Pixel 8 Pro audit found diagnostics work on the cold startup path and layout work behind the startup splash screen. The application has no full-display measurement, and the scheduled benchmark has no measured reference.

## What Changes

- Measure startup steps separately and remove repeated work before the first screen.
- Defer layout behind the splash screen and measure readiness of the first destination.
- Compare release-derived startup benchmarks with measurements from the same CI lane.

## Capabilities

### New Capabilities

- `android-startup` measures and limits the cost to reach the first usable screen.

### Modified Capabilities

None.

## Impact

Application startup, diagnostics storage and bootstrap, launcher UI, Android benchmarks, and CI.
