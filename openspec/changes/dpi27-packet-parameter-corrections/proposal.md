# Change: Correct DPI-27 packet parameter contracts

Task ID: `RST-1794677915101106`

## Why

The audit and source repair found stale feedback, repeated IPv4 IDs, false SACK drops, TCP settings copied to UDP, repeated boundary candidates, and conflicting timestamp documentation. These faults can change transport behavior and hide the actual supported scope.

## What Changes

- Associate adaptive feedback with the selected attempt and state generation.
- Keep non-atomic raw IPv4 IDs unique within the existing sender lease lifetime.
- Reject a SACK match unless the supported option layout proves it is an option header.
- Preserve fixed UDP TTL when adaptive TCP TTL is enabled.
- Enumerate each candidate once per cycle at byte boundaries.
- Describe and test receive-side timestamp drops without promising outbound removal.
- Remove the incorrect DISOOB target from fake TTL settings without changing its fixed TTL 1.
- BREAKING internal Rust port signatures carry attempt identity. Stored settings and JNI/protobuf field numbers remain compatible.

## Capabilities

### New Capabilities

- `packet-parameter-contracts`: explicit safety and scope rules for existing packet controls.

### Modified Capabilities

- None. Existing strategy methods and activation gates remain.

## Impact

Rust adaptive, decision-port, runtime-services, desync-adapter, proxy-runtime, proxy-config and privileged-ops crates; one existing localized IP-ID explanation; one protobuf comment and wire-test contract. No new dependency or persistent migration.
