# Change: Repair TCP split configuration and write contracts

Task ID: `ENG-1794498355495843`

## Why

The DPI-01 audit found accepted offset fields that have no effect, unchecked offset arithmetic, and lost write progress. Cancellation can also leave progress unknown. These cases must return a controlled error and must not cause request replay.

## What Changes

- BREAKING: reject unsupported Split repetition and malformed extra expression fields instead of accepting them without effect.
- Return a controlled offset error on arithmetic overflow.
- Preserve known partial-write progress and reject unsafe replay after cancellation.
- Keep the configured delay before a nonempty tail in both execution paths.

## Capabilities

### New Capabilities

- `tcp-split-write-contracts`: offset admission, write progress, cancellation, and timing contracts.

### Modified Capabilities

- None.

## Impact

- Rust config, proxy config, desync, desync runtime, and proxy runtime crates.
- Kotlin data-model marker admission if needed for the same accepted configuration.
- No new dependencies, schema fields, raw-packet techniques, or required services.
