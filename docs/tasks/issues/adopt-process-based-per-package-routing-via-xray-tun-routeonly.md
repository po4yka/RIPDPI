---
title: Adopt process-based per-package routing via Xray TUN routeOnly
type: task
status: backlog
area: routing
priority: medium
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Adopt process-based per-package routing via Xray TUN routeOnly #repo/RIPDPI #area/routing #status/backlog 🔼

## Summary

reference Android implementation 2.1.0 (2026-04-17) shipped per-package routing via Xray TUN with `routeOnly` enabled. Adopt the same pattern so RIPDPI users can route VPN-detection-positive Russian apps (Sber, RuStore, Wildberries, T-Bank, etc.) directly while everything else goes through VLESS — addressing the platform-VPN-detection regime active since 2026-04-15.

## Research citation

ripdpi-android-research-2026-04-25 §Peer mobile clients — reference Android implementation 2.1.0 added process/package-name-based routing (Android 10+, requires Xray TUN with `routeOnly` enabled) and outbound alias support for traffic-splitting to different servers via Xray TUN. The pattern complements the platform-VPN-detection regime that began enforcement on 2026-04-15 (RKS Global: 22/30 top Russian apps detect VPN, 19/30 report VPN status server-side; see `platform-vpn-detection-april-2026`).

## Acceptance criteria

- [ ] TUN bridge enables `routeOnly` mode per reference Android implementation 2.1.0 reference
- [ ] UI exposes per-package allowlist (route through tunnel) and blocklist (route direct)
- [ ] Default blocklist seeds with VPN-detection-positive apps (RuStore, Sber, Wildberries) per platform-vpn-detection-april-2026
- [ ] Integration test verifies blocklisted apps egress with non-tunnel IP while allowed apps go through VLESS

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Advanced routing rules and geoip enforcement]]
- Research: ripdpi-android-research-2026-04-25 §Peer mobile clients


## amneziawg-outbound-support
