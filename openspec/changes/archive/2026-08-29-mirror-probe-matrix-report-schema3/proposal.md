# Change: Mirror probe matrix report schema 3

Task ID: `DAT-1787994690722107`

## Why

RIPDPI vendors deployment-owned JSON Schemas for cross-repository contract
validation. The deployment producer now publishes probe-matrix report schema
3, while the client mirror still describes the earlier report shape. Leaving
the mirror stale makes the contract gate unable to detect incompatible report
evidence.

## What Changes

- Replace the vendored probe-matrix report schema with the byte-for-byte
  producer contract frozen at
  `po4yka/ripdpi-vpn-deploy@ef688f2a785173913e6e22c42a4843f1c97451bb`.
- Validate the mirror as JSON and through the complete repository contract
  mirror gate.
- Preserve client runtime behavior and all schema 2 window semantics; this
  change only updates test-resource contract authority.

## Capabilities

### New Capabilities

- `data/deployment-contract-mirrors`: RIPDPI verifies that vendored deployment
  contracts are byte-identical to their frozen producer revisions.

### Modified Capabilities

- None.

## Impact

- Affects only
  `core/data/src/test/resources/contract/probe-matrix-report.schema.json` and
  its repository task/OpenSpec records.
- Does not change application code, runtime report parsing, network exposure,
  or device behavior.
