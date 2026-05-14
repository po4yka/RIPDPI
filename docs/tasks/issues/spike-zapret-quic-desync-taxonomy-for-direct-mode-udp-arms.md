---
title: Spike zapret QUIC desync taxonomy for direct-mode UDP arms
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Spike zapret QUIC desync taxonomy for direct-mode UDP arms #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `spike-zapret-quic-desync-taxonomy-for-direct-mode-udp-arms`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-desync/**`, `native/rust/crates/ripdpi-desync-runtime/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Catalogue zapret's QUIC and UDP desync strategies by primitive, map each
to a candidate direct-mode UDP arm, and recommend which arms the
transport policy engine should add first.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Strategy-pack projects — zapret
maintains the closest neighbor to our transparent-mode arm taxonomy, and
its QUIC/UDP desync is load-bearing for HTTP/3 targets (YouTube).
Cross-checking before inventing our own UDP arm taxonomy avoids
duplicate work and gives a shared vocabulary with the peer community.

## Acceptance criteria

- [ ] zapret QUIC/UDP desync strategies catalogued by primitive (fake
    packet, TTL game, header split, payload split, etc.).
- [ ] Each primitive mapped to a candidate UDP arm or marked unmappable
    with a short reason.
- [ ] Recommendation on which one or two arms to add first to the
    transport policy engine, with expected coverage gain.
- [ ] Pointer to zapret source files or docs for each cited primitive.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- [[Gate DoQ on UDP-clean classification]]
- [[Implement QUIC soft-disable per tuple]]
- [[ripdpi-android-research-2026-04-20]]


## dns
