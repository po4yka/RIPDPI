# Change: Complete the AmneziaWG editor

Task ID: `UIX-1795615858043815`

## Why

A user can create an AmneziaWG profile by hand without an external key tool and save it without connecting.

## What Changes

- The editor has a name field and the list shows that name.
- The editor can generate a key pair and shows the public key for copying; the private key stays protected by FLAG_SECURE.
- Save stores the profile without starting the VPN.

## Capabilities

### New Capabilities

- `uix-1795615858043815-complete-amneziawg-editor`: Complete the AmneziaWG editor

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, and gates are constrained by the linked task and design.
