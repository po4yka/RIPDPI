---
title: Implement scoped bootstrap DNS allowlist
type: task
status: backlog
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Implement scoped bootstrap DNS allowlist #repo/RIPDPI #area/vpn #status/backlog ⏫

## Summary

Implement bootstrap DNS that resolves only pinned or allowlisted transport, delivery, and resolver-auth hostnames needed to start the VPN.

## Motivation

Cold-start DNS is a leak risk and a routing-loop risk. RIPDPI should avoid the pattern where system DNS resolves everything until the VPN is connected.

## Scope

- In scope: bootstrap allowlist, qtype limits, short TTL cap, pinned IP preference, last-known-good cache, and explicit bootstrap-failed state.
- Out of scope: general DNS resolution and public resolver benchmarking.

## Acceptance criteria

- [ ] Bootstrap resolver rejects names outside the profile allowlist.
- [ ] Bootstrap `AAAA` is disabled unless the profile IPv6 mode is dual-stack.
- [ ] Pinned endpoint IPs are preferred over system resolution when present.
- [ ] Last-known-good endpoint cache has bounded TTL and is tagged as bootstrap-derived.
- [ ] Bootstrap failure produces a typed state and never enables general ISP DNS fallback.

## Design notes

Bootstrap is allowed to use direct/local DNS only for its tiny scope. Once the VPN is active, normal split-strict DNS policy owns resolution.

## Risks / open questions

- Endpoint DNS migration conflicts with pinning; subscription/profile refresh must be the path for durable endpoint changes.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Bridge TUN traffic through Xray local inbound]]
- [[Add NetworkCallback reconnect and underlying-network tracking]]
