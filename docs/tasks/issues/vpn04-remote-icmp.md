---
id: VPN-1794144829236231
title: Forward remote UDP ICMP errors through the local SOCKS path
kind: feature
status: review
area: vpn
priority: high
owner: VPN remote UDP errors
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn04-remote-icmp
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Deliver proven remote UDP ICMP errors from Linux/Android sockets through the owned local SOCKS path to TUN.

## Acceptance criteria

- Real non-root IPv4 and IPv6 closed-port errors reach the TUN with the original quote.
- Malformed, forged, ambiguous, stale and carrier errors do not create target ICMP.
- Standard SOCKS compatibility, cancellation, memory bounds and checksum gates pass.
- Separate local Linux evidence from unrun Android acceptance.
