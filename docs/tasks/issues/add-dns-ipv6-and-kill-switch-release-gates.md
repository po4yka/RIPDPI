---
title: Add DNS IPv6 and kill-switch release gates
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: epic-vpn-fleet-testing-matrix-and-release-gates
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add DNS IPv6 and kill-switch release gates #repo/RIPDPI #area/testing #status/backlog ⏫

## Summary

Make DNS leak, IPv6 leak, and kill-switch behavior mandatory release gates for
fleet profiles and Android client releases.

## Context

The fleet should not ship profiles that connect but leak DNS/IPv6 or fail open
when the core crashes, the network changes, or the VPN is revoked.

## Acceptance criteria

- [ ] DNS tests verify virtual VPN DNS, proxied DNS through tunneled resolver,
    direct RU DNS only for direct domains, allowlisted bootstrap resolution,
    no ISP fallback on encrypted resolver outage, network-switch behavior,
    core-crash behavior, and Android Private DNS conflict handling.
- [ ] Synthetic authoritative DNS test verifies proxy, direct, and IPv6 query
    sources using unique random domains.
- [ ] IPv4-only tests verify no IPv6 DNS/address/route, no direct IPv6, blocked
    IPv6-only connect, and empty/blocked AAAA behavior.
- [ ] Dual-stack tests verify `::/0` through tunnel and AAAA through tunnel.
- [ ] Kill-switch tests cover forced disconnect, core crash, Wi-Fi/LTE switch,
    sleep/wake, and Android Always-on + Block where applicable.
- [ ] Any DNS leak, IPv6 leak in IPv4-only mode, or Android kill-switch failure
    is a no-ship failure.

## Notes

This task coordinates existing Android DNS/IPv6/kill-switch tasks into release
gates.

## Links

- [[Add DNS interceptor and split DNS leak tests]]
- [[Add explicit IPv6 policy modes and leak tests]]
- [[Add authoritative DNS leak-test harness]]
- [[Add Android lockdown onboarding and kill-switch health checks]]
