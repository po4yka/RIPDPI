# Change: Make the AmneziaWG profile UI establish a real tunnel (standalone AWG transport)

Task ID: `TRN-1786264762917775`

## Why

The AmneziaWgProfileScreen / AwgProfileForm editor lets a user configure a full AmneziaWG peer (endpoint, keys, MTU, DNS, and the Jc/Jmin/Jmax/S1-S2/H1-H4/ I1-I5 obfuscation knobs) — but the app could not run it. The editor was preview-only: no Save/Connect, no persistence, no engine path. This is the same "UI-complete, core-stub" gap as SSH (G1). Distinct from WARP, which only drives Cloudflare's WireGuard endpoints

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `wire-standalone-amneziawg-profile-transport`: Make the AmneziaWG profile UI establish a real tunnel (standalone AWG transport)

### Modified Capabilities

- None.

## Impact

- Portfolio area: `transport`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
