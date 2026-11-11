# Change: Fix XUDP control and target admission

Task ID: `TRN-1794371393565180`

## Why

Valid control frames close the XUDP carrier. Invalid domain targets reach the peer and can close a working association.

## What Changes

- Accept valid metadata-only KEEP and KeepAlive frames without losing the next datagram.
- Reject invalid XUDP domain targets before enqueue. Keep the association usable.

## Capabilities

### New Capabilities

- `xudp-peer-admission`: control frames and domain validation for Single XUDP.

### Modified Capabilities

- None.

## Impact

Only ripdpi-vless and its tests change. No dependencies, public APIs, JNI, storage, configuration, or wire layout change. Existing payload and metadata budgets remain in force. These are compatible parser and input-validation repairs.
