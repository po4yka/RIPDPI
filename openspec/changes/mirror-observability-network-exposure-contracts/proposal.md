# Change: Mirror observability and network exposure contracts

Task ID: `DAT-1788100001077419`

## Why

`ripdpi-vpn-deploy` publishes new observability and signed network-exposure
contracts, while RIPDPI's vendored contract directory does not yet contain
them. The deployment repository compares every canonical contract file with
this directory, so the producer changes cannot integrate until the client
stores exact mirrors.

## What Changes

- Vendor the five observability contract files and two network-exposure JSON
  Schemas byte-for-byte from one frozen producer revision.
- Verify the full vendored directory, JSON validity, repository task/OpenSpec
  state, architecture health, and exact-head hosted CI.
- Keep application and native runtime behavior unchanged.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `data/deployment-contract-mirrors`: include observability and network
  exposure contracts in the producer-owned vendored set.

## Impact

- Affects only test-resource contract mirrors and their task/OpenSpec records.
- Does not implement telemetry, alert delivery, firewall changes, or runtime
  parsing of the new files.
