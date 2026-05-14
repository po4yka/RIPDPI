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

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `audit-cloudflare-only-dependencies`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/data/settings/**`, `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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
