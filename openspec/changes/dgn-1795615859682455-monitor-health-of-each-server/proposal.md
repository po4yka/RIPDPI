# Change: Monitor the health of each server

Task ID: `DGN-1795615859682455`

## Why

The user can check whether each server works and see how the connected server performs.

## What Changes

- The user can run a reachability and latency check for any saved server, AmneziaWG included.
- The Status screen shows latency, traffic, and uptime for the connected server.
- Diagnostics events that concern a server name that server.
- Server identifiers in diagnostics exports follow the existing privacy redaction rules.

## Capabilities

### New Capabilities

- `dgn-1795615859682455-monitor-health-of-each-server`: Monitor the health of each server

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Exact code, contracts, and gates are constrained by the linked task and design.
