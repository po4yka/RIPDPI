# Change: Add network-security-config with opportunistic domainEncryption

Task ID: `DGN-1786264762917626`

## Why

Add res/xml/networksecurityconfig.xml with <domainEncryption mode="opportunistic"/> as the base config, and point AndroidManifest.xml at it. Opportunistic unlocks platform ECH when both the library and DNS say yes

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `add-network-security-config-with-opportunistic-domainencryption`: Add network-security-config with opportunistic domainEncryption

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
