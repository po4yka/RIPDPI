# Change: Repair root-helper IPC audit findings

Task ID: `VPN-1794196711383747`

## Why

The VPN-09 audit found descriptor inheritance and unbounded scalar allocation at the privileged IPC boundary. Request and response deadlines and socket-type checks also need regression evidence. Current main already fixes Kotlin connect and global process-name termination through commit 6808d8e91.

## What Changes

- Keep working sockets close-on-exec after replacement and preserve descriptor ownership on errors.
- Reject ordered TCP lengths and ranges before allocation when the supplied data cannot satisfy them.
- Reproduce and repair late request acceptance, response trickle beyond the total deadline, and invalid socket types when confirmed.
- Verify the existing scoped Kotlin shutdown and bounded socket exchange.
- Retain root opt-in, the non-root baseline, and the v3 wire format. No breaking schema change is planned.

## Capabilities

### New Capabilities

- `root-helper-ipc-safety`: Descriptor lifetime, bounded input, socket validation, and total deadlines for the internal IPC.

### Modified Capabilities

- None.

## Impact

- ripdpi-privileged-ops, ripdpi-root-helper, ripdpi-root-helper-protocol, and ripdpi-runtime-platform.
- Existing :core:service root-helper tests and contract documentation.
- No dependency, lockfile, JNI, protobuf, locale, baseline, or external service change.
