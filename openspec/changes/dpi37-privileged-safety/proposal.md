# Change: Repair privileged operation safety contracts

Task ID: `RST-1794722628748844`

## Why

Three confirmed DPI-37 defects can lose an ICMP response, accept corrupt ICMPv4 data, or hide a failed TCP repair restoration.

## What Changes

- Return supported ICMP receive payloads through a bounded command-specific IPC response.
- Reject corrupt ICMPv4 messages before envelope decoding.
- Preserve terminal repair restoration failures in the remaining raw TCP error paths.
- Keep root activation and unverified audit hypotheses outside this repair.

## Capabilities

### New Capabilities

- `privileged-operation-safety`: bounded replies, input integrity and terminal cleanup.

### Modified Capabilities

- None. Existing public packet mutation behavior and user configuration stay unchanged.

## Impact

- Rust privileged-ops, root-helper-protocol, root-helper and runtime-platform.
- Additive response transport support in helper protocol v4, with peer preflight checks.
- No new dependencies, JNI, protobuf, stored-data or baseline changes.
