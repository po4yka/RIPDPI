---
title: Add Android Private DNS conflict warning
type: task
status: backlog
area: vpn
priority: medium
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add Android Private DNS conflict warning #repo/RIPDPI #area/vpn #status/backlog 🔼

## Summary

Detect and explain Android Private DNS conflicts without treating system Private DNS as RIPDPI's resolver policy.

## Motivation

Users may configure Android Private DNS and assume it protects VPN DNS. RIPDPI owns DNS inside the VPN and should warn about confusing states instead of relying on system Private DNS behavior.

## Scope

- In scope: settings/read-only detection where public APIs allow it, UX warning, diagnostics field, and test coverage for the policy decision.
- Out of scope: modifying the user's Private DNS setting.

## Acceptance criteria

- [ ] DNS settings screen explains that RIPDPI uses its own VPN DNS interceptor.
- [ ] Diagnostics can report `system_private_dns_present`, `ignored_for_vpn_policy`, or `unknown`.
- [ ] App does not route VPN DNS through system Private DNS as a policy source.
- [ ] Warning appears only when it helps explain a resolver mismatch or user confusion.

## Design notes

Keep this educational and diagnostic. It should not block secure VPN startup by itself.

## Risks / open questions

- Android version and OEM differences may limit reliable detection.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Add DNS interceptor and split DNS leak tests]]
