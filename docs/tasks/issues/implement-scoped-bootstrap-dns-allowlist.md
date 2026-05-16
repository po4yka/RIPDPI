---
title: Implement scoped bootstrap DNS allowlist
type: task
status: done
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-16
---

- [x] #task Implement scoped bootstrap DNS allowlist #repo/RIPDPI #area/vpn #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `implement-scoped-bootstrap-dns-allowlist`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Implement bootstrap DNS that resolves only pinned or allowlisted transport, delivery, and resolver-auth hostnames needed to start the VPN.

## Motivation

Cold-start DNS is a leak risk and a routing-loop risk. RIPDPI should avoid the pattern where system DNS resolves everything until the VPN is connected.

## Scope

- In scope: bootstrap allowlist, qtype limits, short TTL cap, pinned IP preference, last-known-good cache, and explicit bootstrap-failed state.
- Out of scope: general DNS resolution and public resolver benchmarking.

## Acceptance criteria

- [x] Bootstrap resolver rejects names outside the profile allowlist.
- [x] Bootstrap `AAAA` is disabled unless the profile IPv6 mode is dual-stack.
- [x] Pinned endpoint IPs are preferred over system resolution when present.
- [x] Last-known-good endpoint cache has bounded TTL and is tagged as bootstrap-derived.
- [x] Bootstrap failure produces a typed state and never enables general ISP DNS fallback.

## Design notes

Bootstrap is allowed to use direct/local DNS only for its tiny scope. Once the VPN is active, normal split-strict DNS policy owns resolution.

## Risks / open questions

- Endpoint DNS migration conflicts with pinning; subscription/profile refresh must be the path for durable endpoint changes.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Bridge TUN traffic through Xray local inbound]]
- [[Add NetworkCallback reconnect and underlying-network tracking]]
