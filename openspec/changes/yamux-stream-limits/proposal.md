# Change: Reject unsafe yamux stream limits

Task ID: `TRN-1794377577809835`

## Why

A profile with 2049 working streams passes admission. The relay builder doubles the value, then yamux panics because the initial stream windows exceed its 1 GiB receive budget.

## What Changes

- Reject unsupported stream limits with an input error before carrier connection.
- Keep the zero default and the reserve for closing streams.
- Validate direct session construction without overflow or panic.
- No wire schema or supported-protocol change.

## Capabilities

### New Capabilities

- `yamux-stream-limits`: bounded configuration admission and session construction.

### Modified Capabilities

- None.

## Impact

- `ripdpi-vless` owns the stream budget and closing reserve.
- `ripdpi-relay-core` stops changing the admitted logical limit.
- MuxConfigError gains a typed limit error; exhaustive Rust matches must handle it. No such repository caller exists.
- No dependency, JNI, locale, or baseline changes.
