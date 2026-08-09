# Change: Add a parallel active-probe race for initial transport selection

Task ID: `TRN-1786264762917886`

## Why

Race the simple flavor's seeded VLESS+Reality and Hysteria2+Salamander relay paths with an application-level probe before the VPN TUN is exposed, select the first confirmed-good transport, and retain the existing post-connection failover and UCB1 behavior

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `parallel-active-probe-race-initial-transport-selection`: Add a parallel active-probe race for initial transport selection

### Modified Capabilities

- None.

## Impact

- Portfolio area: `transport`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
