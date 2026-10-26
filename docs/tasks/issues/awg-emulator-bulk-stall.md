---
id: TRN-1792981644014401
title: AWG IPv6 bulk TCP stall and lost UDP echo on the arm64 emulator
kind: bug
status: backlog
area: transport
priority: high
owner: unassigned
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-26
updated: 2026-10-26
spec_reason: research-only
---

## Goal

Find and fix the cause of the IPv6 bulk TCP stalls and the lost lockstep UDP echoes that earlier arm64 emulator interop runs reported, or show that they do not come from RIPDPI.

## Evidence

Earlier runs (API 35 `google_apis` arm64 emulator, `pixel_6`, amneziawg-go v3.1.20260828 peer, 4 MiB TCP per family plus a 512-datagram lockstep UDP series) used builds with the S3/S4 floor-lift draft:

- S3=S4=0 control: 11/12. One IPv6 upload stalled after about 2 MiB. The loopback capture shows that the client sent no datagram after the stall, which includes its keepalive and its handshake response.
- Full AmneziaVPN 5.x: 6/8. One IPv6 download stall (the peer retransmitted with backoff and the client answered without progress) and one lost UDP echo.
- S3/S4=12 only: 2/3, one lost UDP datagram.

Reproduction attempts on `238d648a0`, control profile, without the draft:

| Build | Load | MTU 1420 | MTU 1280 |
|---|---|---|---|
| clean | idle | 30/30 | 20/20 |
| clean | 4 guest CPU hogs | 24/25 (one lost IPv6 UDP echo) | - |
| instrumented | idle | 42/42 | 80/80 |
| instrumented | 4 or 8 guest CPU hogs | 100/100 | - |

No TCP stall reproduced in 297 sessions. In the one failure, the peer echoed the datagram on host loopback within 0.2 ms, and the client did not send the next datagram. The capture cannot show whether the echo was lost in the emulator network path or in the client.

Host runs of the production runtime against the same peer (4 MiB per family, MTU 1420, 1% loss and 5% reorder through a UDP proxy, and lockstep UDP series) passed 76/76.

The AmneziaVPN 5.x profile needs the S3/S4 floor removed in the native build, so it was not run here.

## Acceptance criteria

- A reproduction that fails on the cause, kept as a regression test in the module that owns the cause.
- At least 20 control and 20 full 5.x arm64 emulator sessions at MTU 1420 and 1280 with matching SHA-256 for every transfer and zero lost UDP echoes.
