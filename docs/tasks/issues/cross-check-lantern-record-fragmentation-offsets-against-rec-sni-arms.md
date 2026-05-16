---
title: Cross-check Lantern record-fragmentation offsets against rec_sni arms
type: task
status: done
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-16
---

- [x] #task Cross-check Lantern record-fragmentation offsets against rec_sni arms #repo/RIPDPI #area/transport #status/done 🔼

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

- [x] Lantern's TLS record-fragmentation offsets enumerated with source
    pointers.
- [x] Diff against `rec_pre_sni` and `rec_mid_sni` neighborhoods
    documented (same / subset / superset / disjoint).
- [x] Recommendation: widen neighborhood, add a new record-split arm, or
    no change — with expected coverage impact.

## Work log

**2026-05-16** — verify exit 0

```
cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-desync --test lantern_rec_sni_coverage
Summary [0.012s] 4 tests run: 4 passed, 0 skipped
```

Files added:
- `native/rust/crates/ripdpi-desync/tests/lantern_rec_sni_coverage.rs` — 4 deterministic unit tests
- `native/rust/crates/ripdpi-desync/docs/lantern_rec_sni_coverage.md` — gap analysis doc

Recommendation: **no change** — `rec_pre_sni` (SniExt+0) already covers Lantern's canonical
split point; `rec_mid_sni` uses a different base (MidSld) and provides complementary coverage.

## Links

- [[Epic - Semantic TLS first-flight family engine]]
- [[Implement TLS record-split family arms]]
- [[Rotate successful family through variant neighborhood]]
- [[ripdpi-android-research-2026-04-20]]
