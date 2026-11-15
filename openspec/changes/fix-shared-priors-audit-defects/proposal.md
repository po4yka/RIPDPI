# Change: Fix signed shared-priors audit defects

Task ID: `SVC-1794755208139697`

## Why

AUX-05 admits unbounded HTTP buffering before its body limits, ignores documented Gradle URL properties, and computes an incorrect mean for large finite parameters.

## What Changes

- Enforce body limits before conversion for success and error responses.
- Read both release URLs from Gradle properties, local properties or environment.
- Compute a stable prior mean without changing payload or signing formats.
- No breaking contracts, new dependencies, release activation or secret changes.

## Capabilities

### New Capabilities

- `shared-priors-admission`: bounded download, build provisioning and prior arithmetic.

### Modified Capabilities

- None.

## Impact

- core/service downloader and build configuration; build-logic resolver; ripdpi-runtime-strategy arithmetic.
