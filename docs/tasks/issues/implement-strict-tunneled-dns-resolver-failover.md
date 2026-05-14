---
title: Implement strict tunneled DNS resolver failover
type: task
status: backlog
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-01
---

- [ ] #task Implement strict tunneled DNS resolver failover #repo/RIPDPI #area/vpn #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `implement-strict-tunneled-dns-resolver-failover`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add strict encrypted DNS failover for proxied/default domains: retry encrypted resolvers and allowed fallback outbounds, then fail closed with no plaintext local fallback.

## Motivation

The most dangerous DNS bug is turning resolver outage into an ISP DNS leak. Proxied domains must fail closed or use an encrypted backup path.

## Scope

- In scope: primary/secondary encrypted resolver order, fallback outbound list, strict failure state, cache use, and resolver outage tests.
- Out of scope: direct RU DNS fallback and server-side resolver operation.

## Acceptance criteria

- [ ] Proxy DNS tries configured encrypted resolvers through the active outbound first.
- [ ] If active outbound DNS fails, only explicitly allowed encrypted DNS fallback outbounds are attempted.
- [ ] Total failure returns `DNS_FAILED_STRICT` or equivalent and `SERVFAIL`/blocked state to callers.
- [ ] No code path uses system/local plaintext DNS for proxy/default domains after strict failure.
- [ ] Tests cover remote DoH block, DoT block, DoQ block, proxy-outbound failure, and cache-assisted recovery.

## Design notes

DoH POST should be the default hostile-network resolver; DoT and DoQ remain profile-controlled options.

## Risks / open questions

- Resolver retry cadence can become a fingerprint if it is too regular across users; keep health checks scoped and backoff-driven.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Build DoH primary and secondary resolver pipeline]]
- [[Gate DoQ on UDP-clean classification]]
