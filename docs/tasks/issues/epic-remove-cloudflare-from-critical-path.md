---
title: Epic - Remove Cloudflare from critical path
type: epic
status: backlog
area: relay
priority: critical
status_note: code/automation landed across client + deploy; non-Cloudflare hosts await operator provisioning
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-30
---

- [ ] #task Epic - Remove Cloudflare from critical path #repo/RIPDPI #area/relay #status/in-progress 🔺

## Goal

Remove Cloudflare from every critical path for Russian users while keeping it as an optional low-priority fallback where it still works.

## Scope

- In scope: dependency audit, non-Cloudflare delivery, non-Cloudflare DNS fallback, direct/non-CF HTTPS fallback, client selector changes, large-payload health checks, per-ISP monitoring, and migration runbook.
- Out of scope: deleting all Cloudflare usage, Cloudflare enterprise static IP procurement, and storing live endpoints or tokens in TaskNotes.

## Status

In progress. Cross-project resilience epic derived from the 2026-05-01 Cloudflare RU degradation brief. As of 2026-05-30 the client-side gating and the deploy-side non-Cloudflare delivery/monitoring automation have all landed in code (see Resolution). The remaining work is operator provisioning of real non-Cloudflare hosts and a non-Cloudflare DNS path; the epic stays open until those are in place.

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

- [ ] No production profile requires Cloudflare for primary transport. — *Code/automation landed; operator action pending.* The client now gates Cloudflare binary extraction to publish mode (`b7b32df5b`) so non-publish profiles no longer pull in the Cloudflare path, and a direct non-CDN HTTPS XHTTP frontend exists on the deploy side (`79f2f5e`). Whether a given production profile actually avoids Cloudflare depends on the operator deploying the non-CDN frontend on a real host and pointing profiles at it.
- [ ] Subscription delivery works through at least one non-Cloudflare endpoint. — *Code/automation landed; operator action pending.* The deploy repo adds an opt-in continuous payload mirror on the subscription host (`5ab17cf`). It is opt-in and requires the operator to enable it and provision the mirror endpoint before this is true in production.
- [ ] DNS bootstrap and tunneled DNS have non-Cloudflare paths. — *Not addressed by this batch.* None of the landed commits touch the resolver chain; this milestone remains open (tracked separately, e.g. the DNS-Morph bootstrap spike).
- [ ] Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded. — *Code/automation landed; operator action pending.* The client gating (`b7b32df5b`) keeps Cloudflare off the default non-publish path, and the non-CDN XHTTP fallback frontend (`79f2f5e`) provides the alternative to fail over to. End-to-end "demote when degraded" still depends on operator selector/priority configuration against live endpoints, so this is not yet fully done.
- [x] Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS success. — Deploy repo adds a per-ASN ~16 KiB payload-throttling probe (`a2d4d06`); the detection capability — distinct from plain TLS-success checks — is implemented. (Continuous coverage across all RU ASNs still depends on operator-run probe hosts, but the throttling-detection automation itself has landed.)

## Risks

- Direct fallback hostnames change the origin exposure threat model.
- Alternative CDNs can become the same failure class if all choices are foreign hyperscale edges.
- Adding multiple delivery mirrors must not create shared subscription URLs or token leakage.

## Notes

Keep live hostnames, tokens, and provider details out of this note. Store sensitive operational mapping under `ops/live-infra/`.

## Resolution

Status as of 2026-05-30: **code/automation landed in both repos; epic stays open pending operator provisioning of real non-Cloudflare hosts and a non-Cloudflare DNS path.** No live hostnames or tokens are recorded here.

What landed, and WHERE:

- Client (this repo, RIPDPI):
  - `b7b32df5b` — fix(service): gate Cloudflare publish binary extraction to publish mode. Cloudflare binary extraction now only happens in publish mode, so non-publish profiles no longer pull the Cloudflare code path onto the critical path.
- Deploy repo (ripdpi-vpn-deploy):
  - `5ab17cf` — feat(subscription-host): add opt-in continuous payload mirror. Provides a non-Cloudflare subscription delivery endpoint (opt-in; operator must enable and provision the mirror host).
  - `79f2f5e` — feat(nginx-xhttp): add opt-in direct non-CDN HTTPS XHTTP fallback frontend. Provides a direct, non-Cloudflare HTTPS XHTTP frontend to fail over to (opt-in; operator must deploy it on a real host).
  - `a2d4d06` — feat(monitoring): add per-ASN ~16 KiB payload-throttling probe. Detects Cloudflare-like large-payload throttling rather than relying on TLS-handshake success alone.

Honest milestone state:

- Met (code): 16 KB payload-throttling monitoring (`a2d4d06`).
- Code/automation landed, operator action pending: no-Cloudflare primary transport, non-Cloudflare subscription delivery, Cloudflare demotion-when-degraded. The code and automation exist (client gating + deploy-side opt-in mirror/frontend), but each requires the operator to provision and enable real non-Cloudflare hosts before it is true in production.
- Not addressed by this batch: non-Cloudflare DNS bootstrap / tunneled DNS path — tracked separately.

## Links

- cloudflare-ru-critical-path-removal-2026-05-01
- vps-proxy-fleet
- [[ripdpi-android]]
- [[Epic - Fail-closed Android VPN policy engine]]
- Epic - Subscription and profile import
- Child issues: 6
