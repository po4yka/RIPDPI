# Change: Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel

Task ID: `RST-1786264762917569`

## Why

The 2026-06-10 architecture audit found two new actionable layering violations (both upward dependencies into the relay-transport layer L7):

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `introduce-ws-transport-port-to-fix-layer-violations`: Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
