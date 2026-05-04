---
title: Add Android VPN leak-test instrumentation matrix
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

- [ ] #task Add Android VPN leak-test instrumentation matrix #repo/RIPDPI #area/vpn #status/backlog ⏫

## Summary

Create an Android VPN leak-test matrix that exercises DNS, IPv6, kill-switch, network transition, revoke, per-app, and credential-revocation behavior across supported API levels.

## Context

The policy-first client is only credible if the failure modes are reproducible. This task collects the cross-cutting instrumentation and acceptance matrix rather than leaving each feature to test only its happy path.

## Acceptance criteria

- [ ] DNS leak test proves proxied domains do not use ISP/default-network DNS.
- [ ] IPv6 leak test proves IPv4-only mode does not expose direct IPv6 on IPv6-capable networks.
- [ ] Core-crash and service-stop tests prove traffic is blocked or the VPN state is revoked, not silently direct.
- [ ] Wi-Fi to LTE, LTE to Wi-Fi, sleep/wake, and captive portal transitions are covered.
- [ ] `onRevoke()` test verifies sockets, TUN fd, and provider runtimes close.
- [ ] Per-app allow/disallow tests cover reconnect requirement and lockdown interactions.
- [ ] Revoked credential fixtures prove stale UUID/shortId/password/profile tokens no longer work in local validation paths.

## Notes

Start with emulator and fake-network harness coverage, then add real-device smoke cases for API 26, 29, 30, 33, 34, 35, and current preview when available.

## Links

- [[Epic - Fail-closed Android VPN policy engine]]
- [[Epic - Orchestration test posture]]
- [[Add Xray VPN client regression matrix]]
