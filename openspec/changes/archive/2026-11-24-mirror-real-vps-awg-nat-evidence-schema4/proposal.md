# Change: Mirror real VPS AWG NAT evidence schema 4

Task ID: `DAT-1788656601400373`

## Why

The deployment producer added the version 4 evidence schema for the recurring
real-VPS AmneziaWG NAT acceptance lane. RIPDPI does not yet vendor this
producer-owned contract, leaving the cross-repository contract set incomplete
and unable to detect drift in the evidence format.

## What Changes

- Add a byte-for-byte vendored copy of the producer's real-VPS AWG NAT evidence
  schema from `po4yka/ripdpi-vpn-deploy@c8ad0861711eb5fb63c6fad46c28c179678d51a5`.
- Extend the deployment-contract mirror specification with the frozen source,
  exact byte-identity requirement, JSON Schema validity, and scope boundary.
- Keep all Kotlin, Rust, signer, relay, runtime, device, and deployment
  behavior unchanged.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `data/deployment-contract-mirrors`: Vendor and verify the producer-owned
  real-VPS AWG NAT evidence schema 4 as a schema-only contract resource.

## Impact

- Affected contract:
  `core/data/src/test/resources/contract/real-vps-awg-nat-evidence.schema.json`.
- Producer source:
  `po4yka/ripdpi-vpn-deploy@c8ad0861711eb5fb63c6fad46c28c179678d51a5`.
- The Android application has no runtime consumer of this test resource.
