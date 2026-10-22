# Change: Export offline device migration receipts after exact new-path tests

Task ID: `TRN-1792673614153980`

## Why

An operator replacing a server needs to distinguish a device that downloaded the
replacement subscription from a device that exercised its new data-plane path.
RIPDPI currently retains refreshed profiles and performs real candidate probes,
but cannot export delivery-bound evidence for safe retirement of the old node.

## What Changes

- Accept per-delivery migration metadata in the sing-box RIPDPI extension and
  retain an exact-response digest only after successful profile persistence.
- Add an explicit test of declared new targets using real native relay probes or
  the exact active AmneziaWG configuration; never infer success from receipt alone.
- Display received and tested states separately and export a bounded, secret-free,
  authenticated JSON receipt only through a user-triggered local share action.
- Keep operation offline-first and require no automatic receipt upload.
- Additive subscription metadata and local state; no existing runtime wire schema
  or public profile format is replaced.

## Capabilities

### New Capabilities

- `transport/device-server-migration`: delivery-bound client storage, exact new-path
  verification and explicit offline receipt export.

### Modified Capabilities

- None.

## Impact

- `:core:data:runtime-state`: strict sing-box metadata, bounded raw response reads,
  serializable subscription receipt state and export contract.
- `:core:data`: subscription-owned AmneziaWG identity and durable profile mapping.
- `:core:service`: existing candidate relay measurements and generation-fenced
  active AmneziaWG verification; no new native transport implementation.
- `:app`: refresh/bootstrap integration, subscription status actions, share files,
  all locale resources and focused UI/behavior tests.
- The shared contract is coordinated with the deployment repository's
  `operations/device-server-migration` capability; no new production dependency.
