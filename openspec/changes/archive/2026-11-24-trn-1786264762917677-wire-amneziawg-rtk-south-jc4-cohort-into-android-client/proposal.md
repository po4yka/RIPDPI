# Change: Wire AmneziaWG RTK South cohort (Jc=4) into Android client

Task ID: `TRN-1786264762917677`

## Why

Plain WireGuard on the observed regional network path experiences periodic 20–30 second interruptions every ~30 seconds — middlebox/device fingerprinting can identify WireGuard via the deterministic 148-byte Initiation packet structure (4-byte type, 4-byte sender index, 32-byte ephemeral public key, 48-byte encrypted static key, 28-byte encrypted timestamp, 16-byte MAC1, 16-byte MAC2). AmneziaWG (AWG) randomizes this signature with junk/header/initialization parameters

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `wire-amneziawg-rtk-south-jc4-cohort-into-android-client`: Wire AmneziaWG RTK South cohort (Jc=4) into Android client

### Modified Capabilities

- None.

## Impact

- Portfolio area: `transport`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
