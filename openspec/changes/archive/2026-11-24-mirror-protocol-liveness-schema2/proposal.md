# Change: Mirror protocol liveness schema 2

Task ID: `DAT-1788011816707517`

## Why

The deployment producer now binds each protocol-liveness sentinel policy to an
exact inventory alias, public-service address hash, deployable digest, and
application time. RIPDPI still vendors schema 1, so the cross-repository mirror
is stale and the deployment contract-sync gate cannot accept the producer.

## What Changes

- **BREAKING**: Replace the vendored protocol-liveness schema with producer
  schema 2, which rejects schema 1 policies and requires every sentinel to
  carry the exact deployment target binding.
- Keep all other 21 vendored contracts byte-identical and unchanged.
- Keep Kotlin, Rust, network-exposure, device, and emulator behavior unchanged.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `data/deployment-contract-mirrors`: Extend frozen producer byte identity and
  schema-only scope isolation to the protocol-liveness schema 2 mirror.

## Impact

- Affected contract:
  `core/data/src/test/resources/contract/protocol-liveness.schema.json`.
- Producer source:
  `po4yka/ripdpi-vpn-deploy@8396ec8c954eda64ae4b78dc1c8f2d18de207c3b`.
- Existing schema 1 policy documents are intentionally invalid under schema 2;
  the Android application has no runtime consumer of this test resource.
