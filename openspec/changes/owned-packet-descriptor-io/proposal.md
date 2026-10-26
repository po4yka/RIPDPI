# Change: Adopt owned packet descriptors without TUN ioctl probes

Task ID: `VPN-1793035703372515`

## Why

The tunnel accepts an owned descriptor from its platform. Its packet loop needs raw packet I/O, not interface creation or configuration. tun-rs 2.8.14 now checks Linux TUN metadata during adoption. This rejects the socketpair descriptors used by the full packet-loop tests. CI run 40366569976 fails six TUN tests in both workspace tests and coverage.

## What Changes

- Use one nonblocking packet-descriptor adapter for platform and socketpair inputs.
- Keep packet bytes, readiness, cancellation, and descriptor ownership unchanged.
- BREAKING: the low-level Rust packet-loop API accepts the owned packet adapter instead of tun-rs AsyncDevice. The Android JNI and run_tunnel interfaces stay the same.

## Capabilities

### New Capabilities

- `owned-packet-io`: nonblocking raw packet I/O for an already-owned descriptor.

### Modified Capabilities

- None.

## Impact

- ripdpi-tunnel-core adapter and packet-loop type references.
- Existing Tokio and libc dependencies; tun-rs remains the interface driver dependency.
- Seven socketpair TUN E2E tests, crate tests, native builds, and hosted Rust coverage.
