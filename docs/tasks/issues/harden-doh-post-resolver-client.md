---
title: Harden DoH POST resolver client
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

- [ ] #task Harden DoH POST resolver client #repo/RIPDPI #area/vpn #status/backlog ⏫

## Summary

Make DoH POST the privacy-first runtime DNS path for proxied domains and harden it against URL/query logging, cache leakage, and resolver authentication mistakes.

## Motivation

DoH GET encodes the DNS query in the URL. RIPDPI's runtime resolver should prefer POST, no-store semantics, authenticated TLS, and no logging of request body, path, or query.

## Scope

- In scope: DoH POST runtime mode, no-store headers, resolver auth name, pinned bootstrap IP support, response validation, and redacted diagnostics.
- Out of scope: DoH JSON runtime resolver and public resolver survey probes.

## Acceptance criteria

- [ ] Runtime proxy DNS uses DoH POST by default for encrypted DNS.
- [ ] DoH GET is disabled for runtime resolver unless a profile explicitly enables it for compatibility.
- [ ] Request URL, body, domain, and response payload are absent from release logs and diagnostics by default.
- [ ] Resolver TLS authentication validates the expected auth name and configured trust/pin policy.
- [ ] DoH failure integrates with strict tunneled resolver failover and never falls back to plaintext local DNS.

## Design notes

Diagnostics may still probe DoH JSON or GET as separate evidence sources, but runtime resolution should remain wire-format POST by default.

## Risks / open questions

- Some resolvers may behave differently for POST and GET; keep this as a profile capability, not a silent fallback.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Build DoH primary and secondary resolver pipeline]]
- [[Add DoH JSON API resolver path alongside RFC 8484 wire]]
