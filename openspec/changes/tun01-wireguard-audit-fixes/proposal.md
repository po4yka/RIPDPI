# Change: Repair WireGuard tunnel audit findings

Task ID: `TRN-1794383863211672`

## Why

The TUN-01 audit found retained import policy, missing plain-WireGuard padding, missing authenticated endpoint roaming, incomplete AllowedIPs enforcement, lost empty UDP, IPv4-only hostname selection, missing count limits and missing retry jitter.

## What Changes

- Replace source-owned fields on manual import and reject explicit empty AllowedIPs.
- Preserve valid UDP datagrams, including empty payloads.
- Enforce plain WireGuard padding, peer endpoint authentication, counter limits and timer rules.
- Pass profile AllowedIPs to native execution and enforce both source and destination policy.
- BREAKING: a profile DNS address outside its AllowedIPs is rejected instead of silently broadening the peer policy.

## Capabilities

### New Capabilities

- `wireguard-tunnel-conformance`: complete the audited single-peer WireGuard contracts.

### Modified Capabilities

- None. Existing AWG import field-presence requirements remain in force.

## Impact

Affected modules are app, core:data, core:engine-api, core:engine, core:service, ripdpi-warp-core and the pinned boringtun vendor. The native JSON config gains a defaulted AllowedIPs field. No dependency, persistent schema, root mode or external service is added.

## Cookie source follow-up

The final review found that plain cookie admission drops the observed UDP port. Repair this mandatory WireGuard source-binding contract in the same change. Keep legacy IP-only callers explicit and preserve AWG admission behavior.
