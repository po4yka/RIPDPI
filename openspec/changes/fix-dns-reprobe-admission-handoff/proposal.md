# Change: Keep scan admission reserved during DNS re-probe handoff

Task ID: `DGN-1790433094681403`

## Why

The primary hidden scan unregisters before DNS-corrected re-probe preparation completes. A concurrent manual or automatic scan can enter that gap and overlap the re-probe.

## What Changes

- Prepare and reserve the hidden re-probe while the primary scan still owns its admission slot.
- Release the primary bridge after the hidden slot is registered.
- Always clean the primary bridge on preparation, registration, or cancellation failure.

## Capabilities

### Modified Capabilities

- `diagnostics/scan-admission`: The primary-to-re-probe handoff preserves exclusive scan admission.

## Impact

- `:core:diagnostics` scan execution coordination and tests. No JNI, wire, or storage schema change.
