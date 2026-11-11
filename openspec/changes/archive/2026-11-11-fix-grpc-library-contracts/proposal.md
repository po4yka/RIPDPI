# Change: Fix gRPC library stream contracts

Task ID: `TRN-1794371438573712`

## Why

The TRN-05 audit found early flush completion, incorrect service escaping, detached decoder lifetime, and no carrier error channel.

## What Changes

- Flush and shutdown drain framed output and return write errors.
- Encode logical service names with Xray-compatible escaping.
- Own decoding in the transport so drop cancels pending work.
- BREAKING: require explicit carrier completion before successful response EOF.

## Capabilities

### New Capabilities

- `grpc-library-stream`: Framing, path, completion, and cancellation contracts.

### Modified Capabilities

- None.

## Impact

Only ripdpi-xhttp and its tests change. No production caller uses this API. No new dependency, application transport, schema, device, or deployment is required.
