# Change: Wire NaiveProxy helper probe into manager startup

Task ID: `SVC-1786264762917506`

## Why

The helper-side --probe line and Kotlin parser now exist. Finish the Android startup integration by invoking --probe before launch, rejecting unsupported schema versions, and documenting the enforced policy

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `wire-naiveproxy-probe-into-manager-startup`: Wire NaiveProxy helper probe into manager startup

### Modified Capabilities

- None.

## Impact

- Portfolio area: `service`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
