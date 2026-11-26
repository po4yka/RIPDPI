# Change: Require a volume-moving transfer for the connection test verdict

Task ID: `DGN-1795704789266331`

## Why

A connection test that reports Passed has moved a payload larger than the TCP-freeze threshold and checked it byte-exact, not only a handshake or a small request.

## What Changes

- The connection test moves a payload larger than the TCP-freeze threshold through the active route and checks its length and hash against the expected values of the operator-owned payload controls.
- A test that completes the handshake and a small request but stalls before the full payload does not report Passed and records the byte count where the transfer stopped.
- The test adds no third-party host. When the server publishes no payload control, the verdict states that volume was not tested instead of Passed.
- The test reuses the existing transfer measurement and stop evidence and adds no second transfer engine.

## Capabilities

### New Capabilities

- `dgn-1795704789266331-require-a-volume-moving-transfer-for-the-connection-test-verdict`: Require a volume-moving transfer for the connection test verdict

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
