---
title: Audit Cloudflare-only dependencies
type: task
status: backlog
area: relay
priority: critical
owner: unassigned
parent: epic-remove-cloudflare-from-critical-path
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Audit Cloudflare-only dependencies #repo/RIPDPI #area/relay #status/backlog 🔺

## Summary

Find every Cloudflare-only dependency in the fleet, client profiles, subscription delivery, DNS, public site, API/update path, and emergency access flows.

## Context

Cloudflare must be treated as a degraded/failable edge for Russian users. The first step is to identify single points of failure before building replacement paths.

## Acceptance criteria

- [ ] Inventory every Cloudflare-backed delivery hostname, subscription URL, DoH/DoT/DoQ resolver, XHTTP frontend, public site, API/update endpoint, Worker/Pages/Tunnel, and reverse-proxy path.
- [ ] Classify each dependency as primary, fallback, optional, or unused.
- [ ] Mark which dependencies currently block IP rotation, subscription refresh, profile recovery, or emergency migration if Cloudflare is unreachable.
- [ ] Assign a non-Cloudflare replacement or fallback plan to each critical dependency.
- [ ] Store live hostnames and sensitive findings only in `ops/live-infra/`; keep TaskNotes summary sanitized.

## Notes

This audit should happen before any DNS-only flip or origin exposure.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[cloudflare-ru-critical-path-removal-2026-05-01]]
