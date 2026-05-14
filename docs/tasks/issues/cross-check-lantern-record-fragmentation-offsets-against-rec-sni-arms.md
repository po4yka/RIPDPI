---
title: Cross-check Lantern record-fragmentation offsets against rec_sni arms
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Cross-check Lantern record-fragmentation offsets against rec_sni arms #repo/RIPDPI #area/transport #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `cross-check-lantern-record-fragmentation-offsets-against-rec-sni-arms`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-desync`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-desync/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Enumerate Lantern's published TLS record-fragmentation split offsets and
diff them against RIPDPI's `rec_pre_sni` and `rec_mid_sni` neighborhoods,
then recommend whether to widen our neighborhoods.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Peer mobile clients — Lantern
Unbounded fragments the TLS handshake across records so SNI straddles a
record boundary. Our `rec_*_sni` arms exist in the same family; making
sure we cover their offsets de-risks field regressions where Lantern
works and RIPDPI does not.

## Acceptance criteria

- [ ] Lantern's TLS record-fragmentation offsets enumerated with source
    pointers.
- [ ] Diff against `rec_pre_sni` and `rec_mid_sni` neighborhoods
    documented (same / subset / superset / disjoint).
- [ ] Recommendation: widen neighborhood, add a new record-split arm, or
    no change — with expected coverage impact.

## Links

- [[Epic - Semantic TLS first-flight family engine]]
- [[Implement TLS record-split family arms]]
- [[Rotate successful family through variant neighborhood]]
- [[ripdpi-android-research-2026-04-20]]
