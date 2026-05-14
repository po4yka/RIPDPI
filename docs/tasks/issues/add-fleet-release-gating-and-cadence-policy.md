---
title: Add fleet release gating and cadence policy
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: epic-vpn-fleet-testing-matrix-and-release-gates
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Add fleet release gating and cadence policy #repo/RIPDPI #area/testing #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-fleet-release-gating-and-cadence-policy`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Define daily, weekly, release, staging, production, and client-release gates for
fleet and RIPDPI profile rollouts.

## Context

The final policy needs clear no-ship and warn-only conditions so degraded
profiles are demoted instead of accidentally shipped as primary paths.

## Acceptance criteria

- [ ] Daily cadence covers node/service health, external TCP/443, REALITY
    non-RU connect, HTTPS 64 KB payload, cert expiry, and backup age.
- [ ] Weekly cadence covers RU fixed tests, RU mobile tests, DNS leak, IPv6
    leak, active-probe simulation, revoked credential, and delivery token
    expiry/revocation.
- [ ] Every release/rotation requires full predeploy suite, staging deploy,
    non-RU smoke, RU fixed/mobile smoke, relevant client regression, old
    profile revocation, and fresh backup after deploy.
- [ ] Production deploy requires staging success, non-RU smoke, at least one RU
    fixed pass, at least one RU mobile pass, DNS leak pass, IPv6 leak pass,
    Android kill-switch pass for primary Android profile, old revoked
    credential failure, and delivery token TTL/revocation pass.
- [ ] Client release requires Android API matrix, Wi-Fi/LTE, captive portal,
    IPv6-enabled network, UDP-blocked network, app/core/schema migration,
    package visibility/per-app routing, logcat no secrets, and crash reports
    no secrets.
- [ ] No-ship policy includes Xray validation, sing-box validation, firewall
    validation, DNS leak, IPv6 leak, kill-switch failure, revoked credential
    still connecting, token/full URL logs, public panel response, and primary
    plus fallback on same burned provider/ASN.
- [ ] Warn-only policy covers partial Hysteria2 UDP failure, Cloudflare path
    failure with non-CF paths healthy, and one degraded RU operator with
    selector avoiding it.

## Notes

The release gate should produce a short sanitized report, not raw probe logs.

## Links

- [[vps-fleet-testing-matrix-2026-05-01]]
- [[Add client compatibility regression matrix for fleet profiles]]
- [[Add DNS IPv6 and kill-switch release gates]]


## xray-vpn-client-mode
