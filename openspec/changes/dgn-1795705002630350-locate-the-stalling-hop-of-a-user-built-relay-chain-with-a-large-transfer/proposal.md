# Change: Locate the stalling hop of a user-built relay chain with a large transfer

Task ID: `DGN-1795705002630350`

## Why

For a relay chain that the user built on the client, diagnostics shows the first hop where a large transfer stops.

## What Changes

- For a user-built chain, diagnostics transfers a payload larger than the TCP-freeze threshold through each hop prefix in order and reports the first hop where the volume stops, with the byte count at the stop.
- A server-side cascade is probed as one endpoint, and the report does not claim per-hop results for it.
- The per-hop result reuses the transfer measurement and payload controls of the connection test task.

## Capabilities

### New Capabilities

- `dgn-1795705002630350-locate-the-stalling-hop-of-a-user-built-relay-chain-with-a-large-transfer`: Locate the stalling hop of a user-built relay chain with a large transfer

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
