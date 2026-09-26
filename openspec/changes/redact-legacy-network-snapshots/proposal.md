# Change: Redact legacy network snapshot history on upgrade

Task ID: `DGN-1790436692670903`

## Why

The capture path now redacts network identities before storage, but existing version 13 `network_snapshots.payloadJson` rows still contain raw identifiers and addresses. History retention can be unlimited, so an app upgrade does not remove those values.

## What Changes

- Room version 14 rewrites each readable legacy network snapshot to the capture-time storage-safe representation.
- Unreadable or structurally unsafe payloads are deleted one row at a time; valid snapshots and unrelated history remain.
- Public IP and ASN remain unchanged pending a separate product decision.

## Capabilities

### New Capabilities

- `diagnostics/legacy-network-snapshot-redaction`: existing database rows become storage-safe on upgrade.

## Impact

- `:core:diagnostics-data` Room migration, schema export, and upgrade regression tests.
