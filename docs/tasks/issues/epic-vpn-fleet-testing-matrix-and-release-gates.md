---
title: Epic - VPN fleet testing matrix and release gates
type: epic
status: backlog
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Epic - VPN fleet testing matrix and release gates #repo/RIPDPI #area/testing #status/backlog ⏫

## Goal

Build a testing matrix and release-gating system that classifies fleet and
client failures as server failure, IP block, protocol block, CDN/path
throttling, DNS/domain block, UDP/QUIC block, mobile whitelist mode, client
regression, DNS/IPv6 leak, or kill-switch failure.

## Scope

- In scope: test result schema, predeploy gates, postdeploy smoke tests,
RU fixed/mobile probes, owned-node active-probe simulation, DNS/IPv6 and
kill-switch gates, captive/whitelist tests, client compatibility regression,
automated suite layout, and daily/weekly/release cadence.
- Out of scope: scanning third-party infrastructure, storing live endpoints or
tokens in TaskNotes, and replacing privacy-safe observability with raw logs.

## Status

New cross-project QA epic derived from the 2026-05-01 fleet testing matrix
brief.

## Child work

- [[Define canonical fleet test result schema]]
- [[Add predeploy validation gates for fleet configs]]
- [[Add postdeploy smoke suite for fleet nodes]]
- [[Add RU fixed and mobile network probe matrix]]
- [[Add active-probe simulation suite for owned nodes]]
- [[Add DNS IPv6 and kill-switch release gates]]
- [[Add captive portal and whitelist-mode test cases]]
- [[Add client compatibility regression matrix for fleet profiles]]
- [[Create automated fleet test suite layout]]
- [[Add fleet release gating and cadence policy]]

## Milestones

- [ ] Every test records PASS, WARN, FAIL, or N/A with sanitized context.
- [ ] Predeploy gates block invalid configs, secrets, unsafe certs, and public panels.
- [ ] Postdeploy smoke tests cover service health, payload size, protocols,
    delivery, revocation, and old credential failure.
- [ ] RU fixed/mobile matrix distinguishes IP, protocol, UDP, delivery, and
    whitelist failures.
- [ ] DNS/IPv6/kill-switch gates are mandatory before production profile rollout.
- [ ] Release policy defines no-ship and warn-only failures.

## Risks

- Small health checks can hide Cloudflare/CDN 16 KB-like throttling.
- A single Russian VPS probe is not representative of fixed and mobile user
networks.
- Active-probe simulation can become unsafe if it targets anything except owned
nodes.

## Notes

Live probe hosts, real endpoints, tester identities, and subscription tokens
belong under `ops/live-infra/`, not in this epic.

## Links

- [[vps-fleet-testing-matrix-2026-05-01]]
- [[vps-proxy-fleet]]
- [[ripdpi-android]]
- [[Epic - Privacy-preserving fleet observability]]
- [[Epic - Remove Cloudflare from critical path]]
- [[Epic - Fail-closed Android VPN policy engine]]
- Child issues: 4
