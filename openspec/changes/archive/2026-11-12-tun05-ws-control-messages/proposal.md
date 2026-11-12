# Change: Handle control frames inside the carrier

Task ID: `RST-1794478552541413`

## Why

Valid Ping/Pong currently surface as datagram errors and cause warning logs and retry delays.

## What Changes

- Handle Ping/Pong inside the carrier without blocking inbound traffic.
- Preserve Close handling and binary-only payload validation.
- No breaking change.

## Capabilities

### New Capabilities

- `wg-ws-controls`: carrier-local WebSocket control handling.

### Modified Capabilities

- None.

## Impact

- Only ripdpi-wireguard-ws receive code and normal wire regression tests.
