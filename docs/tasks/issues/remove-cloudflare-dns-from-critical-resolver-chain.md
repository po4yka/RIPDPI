---
title: Remove Cloudflare DNS from critical resolver chain
type: task
status: done
area: relay
priority: high
owner: unassigned
parent: epic-remove-cloudflare-from-critical-path
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-16
---

- [x] #task Remove Cloudflare DNS from critical resolver chain #repo/RIPDPI #area/relay #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `remove-cloudflare-dns-from-critical-resolver-chain`
- **Verify:** `just test-module core:data:settings`
- **Scope (only modify these + this file + the ledger):** `core/data/settings/**`, `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Ensure Cloudflare DNS services are never the only bootstrap resolver, tunneled resolver, or encrypted DNS fallback for RIPDPI profiles.

## Motivation

If Cloudflare is degraded as a network path, Cloudflare DoH/DoT/DoQ can become the same failure domain as Cloudflare edge.

## Scope

- In scope: resolver inventory, profile defaults, bootstrap allowlist, non-CF encrypted resolver backup, and diagnostics warning.
- Out of scope: banning Cloudflare DNS as an optional resolver.

## Acceptance criteria

- [x] No secure profile uses Cloudflare DNS as its only bootstrap or tunneled resolver.
- [x] Tunneled DNS has own-resolver or non-CF encrypted primary/backup options.
- [x] Bootstrap endpoint resolution prefers pinned IPs or tiny direct allowlist, not general Cloudflare DNS.
- [ ] Diagnostics warn when all configured resolver paths share the Cloudflare failure domain.
- [ ] Resolver outage tests prove no fallback to local plaintext DNS for proxied/default domains.

## Design notes

This is a specialization of split-strict DNS policy for the Cloudflare failure domain.

## Risks / open questions

- Public resolver diversity can still centralize metadata; own resolver through tunnel should be evaluated for production profiles.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Implement strict tunneled DNS resolver failover]]


## semantic-tls-first-flight
