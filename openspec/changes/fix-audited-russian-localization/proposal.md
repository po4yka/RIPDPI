# Change: Fix audited Russian localization defects

Task ID: `UIX-1791805113916673`

## Why

The Russian UI has complete resource keys but contains English instructions, incorrect safety explanations, broken counts, and locale selection defects on Android 12 and earlier.

## What Changes

- Correct every confirmed translation defect from the 2026-10-12 audit.
- Preserve the selected language after process restart and use it outside Activity contexts.
- Localize dynamic diagnostics, routing guidance, strategy fields, and notification counts.
- Preserve all ten locale resource sets and existing machine identifiers. No breaking contract change.

## Capabilities

### New Capabilities

- `app-localization`: Correct and persistent app-localized presentation.

### Modified Capabilities

- None.

## Impact

- Kotlin presentation and resources in app and core/service.
- Existing AndroidX locale storage, translation export manifest, and tests.
- No Rust, JNI, protobuf, network, or export schema change. No new dependency.
