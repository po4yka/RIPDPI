---
id: DGN-1795014015067016
title: Add bounded TLS interference hop comparisons
kind: feature
status: backlog
area: diagnostics
priority: medium
owner: diagnostic-evidence writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: tls-interference-hop-comparison
created: 2026-11-18
updated: 2026-11-18
---

## Goal

Current platform_ttl reads the local configured outbound TTL, not router responses or interference location. An opt-in paired control/test SNI hop experiment is a distinct diagnostic, not a reinterpretation of that value.

## Acceptance criteria

- Probe only when platform capabilities and the selected direct network are verified; unsupported ICMP capture or socket TTL operations return unsupported without root escalation or fabricated hops.
- Use a bounded user-started control/test SNI experiment against the same user-authorized endpoint with ordered TTL values, fresh connections, repeated controls and a total cancellation deadline. Do not scan arbitrary address ranges.
- Keep path addresses and timings local; redact raw hops, public IPs and precise timing from ordinary exports. Any diagnostic detail export requires a separate visible choice and disclosure.
- Report missing ICMP, asymmetric paths, load balancing, invalid control, proxy interception and network changes as measurement limits, never proof of no censorship.

## Ownership

One implementation owner serializes native diagnostics contracts, Kotlin engine contracts, persistence and export changes, locales and generated artifacts. This specification-only branch owns these three task records and OpenSpec directories plus taskctl-generated board; no production files.

## Sources and scope

https://ooni.org/post/2026-ooni-probe-tls-middlebox-experiment/

OONI describes paired control and test names with expiring requests. Its advanced path data can expose location; this proposal adopts local-first measurements instead of OONI publication.

No implementation, device probe, credential use or deployment is performed by this planning change.
