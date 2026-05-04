---
title: Define split-strict DNS policy model
type: task
status: backlog
area: vpn
priority: critical
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Define split-strict DNS policy model #repo/RIPDPI #area/vpn #status/backlog 🔺

## Summary

Define a split-strict DNS policy model that separates bootstrap, direct, tunneled, and blocked resolver paths inside the device VPN profile.

## Motivation

RIPDPI should not treat DNS as a single resolver setting. DNS route and connect route must stay coherent, and encrypted resolver failure must not fall back to plaintext local DNS for proxied domains.

## Scope

- In scope: model types for resolver planes, domain classes, qtype policy, strict failure, IPv6 interaction, cache metadata, and profile serialization.
- Out of scope: DNS packet parser implementation and server-side resolver deployment.

## Acceptance criteria

- [ ] Policy model has distinct `bootstrap`, `proxy`, `direct`, and `block/refuse` resolver paths.
- [ ] Proxy/default domains require strict encrypted DNS and cannot fall back to direct plaintext DNS.
- [ ] Direct DNS can be selected only for domains whose connect route is also DIRECT.
- [ ] `AAAA` handling is explicit and tied to the active IPv6 policy.
- [ ] Policy serialization can represent DoH POST, DoT strict, optional DoQ, pinned bootstrap IP, and direct allowlists.

## Design notes

This should feed both Android runtime DNS decisions and profile rendering. It complements, but does not replace, the direct-mode DNS classifier.

## Risks / open questions

- Avoid two parallel DNS policy systems: direct-mode classifier output should map into this runtime policy rather than bypass it.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Define policy bundle profile schema]]
- [[Add DNS interceptor and split DNS leak tests]]
