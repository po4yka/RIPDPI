# Change: Fix Vision stream contracts

Task ID: `TRN-1794371406782186`

## Why

TRN-02 tests found a false Direct decision for TLS 1.2, silent truncation and lost short replies. These defects affect the existing native Vision stream.

## What Changes

- Require a valid TLS 1.3 ServerHello before Direct and handle handshake fragmentation.
- Report incomplete recognized frames and preserve short unpadded replies.
- Test unknown commands and half-close behavior; fix proven violations.
- No valid wire format or public configuration changes. Invalid frame handling can become stricter.

## Capabilities

### New Capabilities

- `vision-stream-contracts`: native Vision detection, framing and stream termination.

### Modified Capabilities

- None.

## Impact

- ripdpi-vless and its local peer tests. No Kotlin, JNI, schema, dependencies or device state changes.
