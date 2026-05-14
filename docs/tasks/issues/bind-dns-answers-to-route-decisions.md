---
title: Bind DNS answers to route decisions
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

- [ ] #task Bind DNS answers to route decisions #repo/RIPDPI #area/vpn #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `bind-dns-answers-to-route-decisions`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-runtime-dns-cache`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-runtime-dns-cache/**`, `native/rust/crates/ripdpi-dns-resolver/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Store DNS answers with resolver-path and route-decision metadata so direct answers are not accidentally reused for proxy routes or the reverse.

## Motivation

Split-brain DNS is a leak and compatibility risk: the same domain can return different CDN answers depending on resolver path, and the connection route must match the DNS decision.

## Scope

- In scope: `ResolvedAnswer` metadata, route-aware cache keys, TTL caps, negative-cache policy, and policy checks before connection.
- Out of scope: FakeIP implementation and large route-rule editor UI.

## Acceptance criteria

- [ ] DNS cache entries record domain, qtype, IPs, resolver path, route decision, expiry, and source policy version.
- [ ] Direct DNS answers are not reused for proxy routes unless policy explicitly permits it.
- [ ] Proxy DNS answers are not reused for direct RU/local routes unless policy explicitly permits it.
- [ ] Negative cache has short bounded TTL and preserves resolver path.
- [ ] Route decision mismatch triggers re-resolution or fail-closed behavior, not silent reuse.

## Design notes

This task is the runtime coherence layer between DNS policy and routing policy.

## Risks / open questions

- Hardcoded-IP connections have no DNS answer to bind. They should be handled by routing rules and diagnostics separately.

## Links

- [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
- [[Add Rust rule matcher with domain ip port process matchers]]
- [[Add geoip.db and geosite.db runtime loader and lookup]]
