# Change: Repair DPI suite probe evidence and execution

Task ID: `DGN-1794745065716076`

## Why

The DPI suite can label missing measurements as network failures. It can also infer throttling and TLS blocking from unrelated observations.

## What Changes

- Preserve TCP16 target admission, HTTP outcomes, and measured train interruptions.
- Compare SNI on a measured peer and identify a missing baseline.
- Measure Telegram legs independently with cancellable I/O.
- Keep ECH and ordinary HTTPS observations separate.
- BREAKING: revise internal probe results and the private ECH JNI response. The Android app and native library ship together.

## Capabilities

### New Capabilities

- `diagnostics/dpi-suite-evidence`: execution and evidence for the Kotlin DPI suite.

### Modified Capabilities

- None.

## Impact

- Kotlin diagnostics, Android presentation, and the Android ECH fetch adapter.
- Security policy and runtime dependency set stay in force.
