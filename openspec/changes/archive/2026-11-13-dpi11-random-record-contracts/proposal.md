# Change: Enforce TLS random-record contracts

Task ID: `RST-1794557083402232`

## Why

DPI-11 accepts invalid native fragment settings, transforms non-handshake records, and can violate user size bounds during adaptive tuning. Direct planner inputs also expose unchecked size products.

## What Changes

- Reject positive native fragment settings outside existing product limits.
- Keep non-handshake input unchanged at the TLS normalization boundary.
- Make partition size products saturate instead of overflow.
- Keep adaptive tail sizes within the saved bounds.
- No new schema, dependency, or activation mode is introduced. Invalid positive settings now fail instead of being accepted.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `tls-clienthello`: enforce random-record safety contracts.

## Impact

- Rust crates ripdpi-proxy-config and ripdpi-desync.
- No JNI or protobuf schema changes, storage migration, root access, or deployment.
