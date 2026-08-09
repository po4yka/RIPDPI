# Change: Wire Hysteria Realm STUN-discovered NAT traversal (sing-box v1.14.0-alpha.22)

Task ID: `TRN-1786264762917675`

## Why

sing-box v1.14.0-alpha.22 (2026-05-11) introduced a Hysteria Realm service that enables direct peer-to-peer Hysteria2 QUIC tunnels between two clients behind separate NATs — without a fixed listening server on a datacenter ASN. Datacenter-path QoS policies, including short-transfer stalls and session-volume caps, can affect conventional Hysteria2 deployments; Realm permits alternate peer placement because the data peer can live on a residential or mobile ASN behind NAT

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `wire-hysteria-realm-stun-nat-traversal`: Wire Hysteria Realm STUN-discovered NAT traversal (sing-box v1.14.0-alpha.22)

### Modified Capabilities

- None.

## Impact

- Portfolio area: `transport`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
