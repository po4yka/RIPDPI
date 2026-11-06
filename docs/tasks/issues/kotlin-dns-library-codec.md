---
id: DNS-1793945565828575
title: Use one library DNS codec for Kotlin clients and probes
kind: bug
status: review
area: dns
priority: high
owner: DNS implementation
parent: null
blocked_by: []
spec_mode: required
openspec_change: kotlin-dns-library-codec
created: 2026-11-06
updated: 2026-11-06
---

## Goal

Use dnsjava for all Kotlin DNS wire messages. Reject unrelated or malformed responses and retry truncated direct resolution over TCP on the same network.

## Acceptance criteria

- Preserve DNS outcome, CNAME, TTL, SOA, EDE and ECH evidence.
- Validate response identity, question, class, opcode and complete records.
- Preserve exact-size EDNS probe payloads and UDP-only health evidence.
- Pass affected unit suites, static analysis and CI source gates, then integrate and push.

## Ownership

- Root owns the shared DNS codec, dependency catalog, direct resolver, task artifacts and integration.
- Diagnostics worker owns DNS semantic and HTTPS record consumers and their tests in an isolated worktree.
- Probe worker owns service DNS probe builders and matching, app debug DNS consumers and their tests in an isolated worktree.
- The dependency catalog and all generated gate outputs have one writer: root.
