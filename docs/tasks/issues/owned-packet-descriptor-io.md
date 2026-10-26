---
id: VPN-1793035703372515
title: Adopt owned packet descriptors without TUN ioctl probes
kind: bug
status: review
area: vpn
priority: high
owner: Dependency integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: owned-packet-descriptor-io
created: 2026-10-26
updated: 2026-10-26
---

## Goal

Keep raw packet descriptor I/O working with tun-rs 2.8.14 and restore the full TUN packet-loop tests.

## Acceptance criteria

- PacketFd preserves packet bytes, descriptor flags, readiness, ownership, and cancellation behavior.
- All existing TUN E2E tests and tunnel-core tests pass without skipped tests or relaxed assertions.
- Clippy, architecture, task contracts, native ABI builds, and hosted Rust coverage pass.
- Android JNI and run_tunnel contracts stay the same; the low-level Rust API change is recorded.
- Completion requires the positive packet I/O capability and observed evidence; a refusal or an unrun gate does not qualify.
