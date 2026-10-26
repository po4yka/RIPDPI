---
id: TRN-1792981644014401
title: AWG IPv6 bulk TCP stall and lost UDP echo on the arm64 emulator
kind: bug
status: review
area: transport
priority: high
owner: Integration owner
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-26
updated: 2026-10-26
spec_reason: regression-tested-single-module
---

## Goal

Find and fix the cause of the IPv6 bulk TCP stalls and the lost lockstep UDP echoes that earlier arm64 emulator interop runs reported.

## Root cause

The tunnel UDP socket (`native/rust/crates/ripdpi-warp-core/src/wireguard/socket.rs`, `bind_tunnel_socket`) kept the kernel default receive buffer, 229376 bytes on Android 15. A 4 MiB download arrives in bursts that overflow it before the receive task drains the socket: one session counted 1376 Udp `RcvbufErrors` in the guest. The gVisor peer then resent the lost run one segment per retransmission timeout with doubling backoff (0.4 s to 25.6 s), so the transfer never finished. The stall is not IPv6-specific: the reproduction stalled an IPv4 download at MTU 1280.

Fix: c70f50658 asks for 7 MiB send and receive buffers, as wireguard-go does. Regression test: `wireguard::socket::tests::tunnel_socket_asks_for_buffers_beyond_the_kernel_default` (fails without the fix).

The lost UDP echoes had two environment causes, which 627f68bfb removes from the harness: the emulated cellular network appearing and disappearing (sends failed with ENETUNREACH), and the post-boot load storm (load above 60) that dropped datagrams in the guest before any socket saw them.

## Evidence

- Earlier runs with the floor-lift draft: control 11/12, full AmneziaVPN 5.x 6/8, S3/S4=12 only 2/3.
- On 238d648a0 without the draft, the control profile passed 297 fresh-peer sessions with one lost UDP echo under CPU load. The four-cycle reconnect loop with the 5.x profile reproduced the stall in 1 of 12 peer runs before the fix.
- After the fix, at 627f68bfb: 160/160 emulator sessions (5.x and control, MTU 1280 and 1420, idle and CPU load) with zero receive-buffer errors and zero lost datagrams; harness 5.x 8/8 plus the wrong-key negative control, harness control 8/8. Details: `openspec/changes/awg3-native-engine/verification.md`.

## Acceptance criteria

- A reproduction that fails on the cause, kept as a regression test in the module that owns the cause.
- At least 20 control and 20 full 5.x arm64 emulator sessions at MTU 1280 and 1420 with matching SHA-256 for every transfer and zero lost UDP echoes.
