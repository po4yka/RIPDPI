# Change: Split the 12-method PolicyPort trait into selection and learning sub-traits

Task ID: `RST-1786264762917192`

## Why

The 2026-06-10 Rust API audit flagged an Interface-Segregation violation. ripdpi-runtime-decision-ports/src/policy.rs:138 — PolicyPort now has 12 methods (threshold 8): selectinitial, notesuccess, advanceroute, noteblocksignal, supportstrigger, selectnext, storeroute, clearconnectioncache, buildretrypenalties, autolearnstate, drainautolearnevents, flushhoststore. Callers that only select routes are forced to depend on (and mock, in tests) the full learning surface

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `split-policyport-trait-selection-learning`: Split the 12-method PolicyPort trait into selection and learning sub-traits

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
