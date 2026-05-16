---
title: Demote Cloudflare profiles from default auto selection
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

- [x] #task Demote Cloudflare profiles from default auto selection #repo/RIPDPI #area/relay #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `demote-cloudflare-profiles-from-default-auto-selection`
- **Verify:** `just test-module core:data:settings`
- **Scope (only modify these + this file + the ledger):** `core/data/settings/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Make Cloudflare-backed XHTTP/HTTPS profiles low-priority or manual-only in the default selector when Russian-path degradation is detected or likely.

## Motivation

Cloudflare can pass TCP/TLS and still fail payload transfer. It should not compete equally with direct REALITY or non-Cloudflare HTTPS fallback in auto mode for Russian users.

## Scope

- In scope: profile capability flag, health-state based demotion, selector ordering, manual override, and UI labels.
- Out of scope: removing Cloudflare support entirely.

## Acceptance criteria

- [x] Default auto candidates prefer direct REALITY and non-Cloudflare HTTPS fallback.
- [x] Cloudflare-backed profiles are excluded from auto when marked degraded.
- [x] Manual selection still allows Cloudflare profile use where it works.
- [x] Selector UI labels Cloudflare paths as optional/edge fallback.
- [x] Tests cover transition from healthy to degraded and back after payload health recovers.

## Design notes

This task complements, but does not replace, the broader failover state machine.

## Risks / open questions

- Some Russian ISPs may still pass Cloudflare; demotion should be health-based, not a global hard block.

## Links

- [[Epic - Remove Cloudflare from critical path]]
- [[Epic - Xray provider mode]]
- [[Add Cloudflare large-payload healthcheck]]
