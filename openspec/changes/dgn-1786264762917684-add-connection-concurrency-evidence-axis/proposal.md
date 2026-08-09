# Change: Add connection-concurrency as an independent evidence axis

Task ID: `DGN-1786264762917684`

## Why

Model TLS fingerprint and same-SNI connection concurrency as independent evidence axes so diagnostics can identify their conjunction without adding another failure-symptom signal

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `add-connection-concurrency-evidence-axis`: Add connection-concurrency as an independent evidence axis

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
