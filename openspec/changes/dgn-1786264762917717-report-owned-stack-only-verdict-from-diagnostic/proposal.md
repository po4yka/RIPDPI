# Change: Report OWNED_STACK_ONLY verdict from diagnostic

Task ID: `DGN-1786264762917717`

## Why

When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10) works, the diagnostic returns OWNEDSTACKONLY. Surface that as a real verdict, not a failure — "open this host inside the RIPDPI browser" is a legitimate outcome

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `report-owned-stack-only-verdict-from-diagnostic`: Report OWNED_STACK_ONLY verdict from diagnostic

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
