# Change: Sign filtered-side node address checks for deploy admission

Task ID: `DGN-1795868945773730`

## Why

An operator runs a diagnostics address check from a device in the filtered network and exports one signed attestation in the deploy contract node-address-check version 1, so the deployer can admit a new node address into RIPDPI bundles. The deploy gate in po4yka/ripdpi-vpn-deploy (OPS-1795704918170150) refuses every RIPDPI bundle path until such an attestation exists.

## What Changes

- The two node-address-check contract files are vendored byte-for-byte, vendored-deploy-contracts.sha256 is updated, and the deploy contract-sync job passes.
- A diagnostics action runs a TCP connect and a TLS handshake to an operator-entered address and port and records each probe result by stage, with ICMP recorded only as a hint.
- The app generates one Ed25519 key per device in Android Keystore or an equivalent hardware-backed store, shows the public key for the operator trust file, and never exports the private key.
- The app exports one attestation file that validates against the vendored schema, and a tampered record fails signature verification in a test.
- The attestation carries only the fields that the schema requires, and the action is not part of automatic diagnostics or field reports.

## Capabilities

### New Capabilities

- `node-address-check-producer`: Sign filtered-side node address checks for deploy admission

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Contract: vendored copy of `node-address-check.schema.json` and `node-address-check.golden.json` under `core/data/src/test/resources/contract/`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
