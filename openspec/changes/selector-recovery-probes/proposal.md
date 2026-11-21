# Schedule bounded selector probes after failures and network changes

Task ID: `VPN-1795271597027505`

## Why

A selector can wait for the full regular interval after losing the current route. Network callbacks can arrive in bursts, and recovery checks need to fit in the same traffic budget as regular checks.

## What Changes

- Coalesce runtime, network, and active-path failure signals into an early payload pass.
- Charge automatic payload checks to the durable byte ledger. Leave room for one recovery pass when the policy can support it.

## Capabilities

### New Capabilities

- `selector-recovery-probes` — Schedule and budget early selector payload checks.

### Modified Capabilities

- None.

## Impact

Automatic payload selectors and their shared traffic budget.
