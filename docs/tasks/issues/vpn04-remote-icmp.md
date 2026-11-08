---
id: VPN-1794144829236231
title: Forward remote UDP ICMP errors through the local SOCKS path
kind: feature
status: done
area: vpn
priority: high
owner: VPN remote UDP errors
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn04-remote-icmp
created: 2026-11-08
updated: 2026-11-08
closed_at: "2026-11-08T14:34:28Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Feature d893cee: 976 unit and 17 integration tests, real non-root Linux dual-family proxy-to-TUN regression, Clippy, ci-gates and independent reviews passed."
---

## Goal

Deliver proven remote UDP ICMP errors from Linux/Android sockets through the owned local SOCKS path to TUN.

## Acceptance criteria

- Real non-root IPv4 and IPv6 closed-port errors reach the TUN with the original quote.
- Malformed, forged, ambiguous, stale and carrier errors do not create target ICMP.
- Standard SOCKS compatibility, cancellation, memory bounds and checksum gates pass.
- Separate local Linux evidence from unrun Android acceptance.
