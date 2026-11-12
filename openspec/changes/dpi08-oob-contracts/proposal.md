# Change: Preserve TCP payload across OOB execution

Task ID: `ENG-1794501878605236`

## Why

Repeated urgent sends can insert bytes into the normal peer stream. Partial
sends lose progress. Parsers accept unsupported values and counters include an
inserted byte as application data.

## What Changes

- Limit urgent emission to the first nonempty outbound buffer, once per plan.
- Send the original prefix with progress tracking, then one urgent byte.
- Reject malformed hex escapes, unsupported OOB repeats and invalid UI bytes.
- Exclude inserted bytes from application counts.
- BREAKING: unsafe repeated OOB parameters are rejected. Later runtime urgent
  steps become ordinary splits. UI characters above byte 255 are rejected.

## Capabilities

### New Capabilities

- `oob-payload-integrity`: bounded urgent emission, progress, byte validation,
  and application accounting.

### Modified Capabilities

- None.

## Impact

- Rust config, desync planner, runtime and owning tests.
- Kotlin settings validation and native configuration mapping.
- No dependency, protobuf, JNI signature, storage schema or backend change.
- No claim of external DPI compatibility or packet capture.
