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

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-cloudflare-degradation-classification-runbook`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/data/settings/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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
