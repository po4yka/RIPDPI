---
title: Add Cloudflare degradation classification runbook
type: task
status: backlog
area: relay
priority: high
owner: unassigned
parent: epic-remove-cloudflare-from-critical-path
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add Cloudflare degradation classification runbook #repo/RIPDPI #area/relay #status/backlog ⏫

## Summary

Create a runbook that distinguishes Cloudflare edge throttling, domain-specific blocking, origin failure, client/protocol failure, and mobile whitelist/shutdown modes.

## Context

Different failures produce similar user reports. The response differs: demote Cloudflare path, rotate hostname, fix origin, patch client protocol, or switch to whitelist-mode guidance.

## Acceptance criteria

- [ ] Runbook defines symptoms and checks for edge throttling, domain block, origin issue, client/protocol issue, and whitelist/shutdown.
- [ ] Includes payload-level checks rather than relying only on TLS handshake.
- [ ] Includes non-Russian control checks to detect origin failures.
- [ ] Includes guidance for when to disable Cloudflare path in auto-selection.
- [ ] Includes guidance for where to store sensitive live findings under `ops/live-infra/`.

## Notes

Keep user-visible state simple: degraded Cloudflare-like path, origin issue, network restricted, or profile issue.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[Add Cloudflare large-payload healthcheck]]
