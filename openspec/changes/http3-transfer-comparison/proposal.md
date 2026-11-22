# Compare verified HTTPS and HTTP/3 transfers

Task ID: `DGN-1795357427018830`

## Why

A successful QUIC handshake cannot tell whether HTTP/3 transfers the same object as HTTPS over TCP.

## What Changes

Add a manual RAW comparison with one pinned origin and one shared deadline. Verify the complete responses and show the first difference and measured byte counts.

## Capabilities

### New Capabilities

- http3-transfer-comparison

### Modified Capabilities

- None.

## Impact

Manual diagnostics planning, native HTTPS and HTTP/3 execution, and report presentation.
