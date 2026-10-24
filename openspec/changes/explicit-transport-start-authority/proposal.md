# Change: Preserve explicit transport start authority

Task ID: `SVC-1792838679434393`

## Why

An explicit AWG activation reaches cold VPN startup without its captured runtime command. The startup guard rejects the command before the native handshake. The service shell accepts the checked command but loses its authority at the activation callback.

## What Changes

- Carry the captured command through explicit transport activation.
- Reject missing or stale commands and cancel superseded activation.
- Preserve automatic failover restart behavior. No wire or storage contract changes.

## Capabilities

### New Capabilities

- `explicit-transport-start-authority`: captured command authority for explicit transport activation.

### Modified Capabilities

- None.

## Impact

- Service command dispatch and focused lifecycle tests in `core/service`.
- No new dependencies, provider actions, or credential changes.
