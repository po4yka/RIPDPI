---
title: Epic - Remove Cloudflare from critical path
type: epic
status: backlog
area: relay
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Epic - Remove Cloudflare from critical path #repo/RIPDPI #area/relay #status/backlog 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-remove-cloudflare-from-critical-path`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Remove Cloudflare from every critical path for Russian users while keeping it as an optional low-priority fallback where it still works.

## Scope

- In scope: dependency audit, non-Cloudflare delivery, non-Cloudflare DNS fallback, direct/non-CF HTTPS fallback, client selector changes, large-payload health checks, per-ISP monitoring, and migration runbook.
- Out of scope: deleting all Cloudflare usage, Cloudflare enterprise static IP procurement, and storing live endpoints or tokens in TaskNotes.

## Status

New cross-project resilience epic derived from the 2026-05-01 Cloudflare RU degradation brief.

## Child work

- Audit Cloudflare-only dependencies (closed task)
- Provision non-Cloudflare delivery host
- Add multi-delivery subscription mirror support (closed task)
- Add Cloudflare large-payload healthcheck (closed task)
- Demote Cloudflare profiles from default auto selection (closed task)
- Add non-Cloudflare HTTPS XHTTP fallback frontend
- Remove Cloudflare DNS from critical resolver chain (closed task)
- Add Cloudflare degradation classification runbook (closed task)
- Add Russian ISP payload monitoring probes

## Milestones

- [ ] No production profile requires Cloudflare for primary transport.
- [ ] Subscription delivery works through at least one non-Cloudflare endpoint.
- [ ] DNS bootstrap and tunneled DNS have non-Cloudflare paths.
- [ ] Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded.
- [ ] Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS success.

## Risks

- Direct fallback hostnames change the origin exposure threat model.
- Alternative CDNs can become the same failure class if all choices are foreign hyperscale edges.
- Adding multiple delivery mirrors must not create shared subscription URLs or token leakage.

## Notes

Keep live hostnames, tokens, and provider details out of this note. Store sensitive operational mapping under `ops/live-infra/`.

## Links

- cloudflare-ru-critical-path-removal-2026-05-01
- vps-proxy-fleet
- [[ripdpi-android]]
- [[Epic - Fail-closed Android VPN policy engine]]
- Epic - Subscription and profile import
- Child issues: 6
