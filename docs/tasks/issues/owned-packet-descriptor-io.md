---
id: VPN-1793035703372515
title: Adopt owned packet descriptors without TUN ioctl probes
kind: bug
status: done
area: vpn
priority: high
owner: Dependency integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: owned-packet-descriptor-io
created: 2026-10-26
updated: 2026-10-26
closed_at: "2026-10-26T19:48:51Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Main 82166c9e357dc30805670f9dbae8faf6c9963a8e passed CI run 40383907052: Rust workspace, coverage, all four native ABIs, Debug and Release, static analysis, and Android API 27/33/35/36/37. Local tunnel-core 326 unit and 7 TUN E2E tests passed; independent reviews passed."
---

## Goal

Keep raw packet descriptor I/O working with tun-rs 2.8.14 and restore the full TUN packet-loop tests.

## Acceptance criteria

- PacketFd preserves packet bytes, descriptor flags, readiness, ownership, and cancellation behavior.
- All existing TUN E2E tests and tunnel-core tests pass without skipped tests or relaxed assertions.
- Clippy, architecture, task contracts, native ABI builds, and hosted Rust coverage pass.
- Android JNI and run_tunnel contracts stay the same; the low-level Rust API change is recorded.
- Completion requires the positive packet I/O capability and observed evidence; a refusal or an unrun gate does not qualify.
