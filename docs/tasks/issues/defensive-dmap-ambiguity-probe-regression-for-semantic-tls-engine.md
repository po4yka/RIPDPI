---
title: Defensive dMAP ambiguity-probe regression for semantic TLS engine
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

- [x] #task Defensive dMAP ambiguity-probe regression for semantic TLS engine #repo/RIPDPI #area/transport #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `defensive-dmap-ambiguity-probe-regression-for-semantic-tls-engine`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-desync`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-desync/**`, `native/rust/crates/ripdpi-desync-runtime/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Replay dMAP-style DPI ambiguity probe sequences against all six named
arms and verify that no rotated family produces a stable ambiguity
fingerprint that a middlebox-class censor could use to identify RIPDPI.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Academic papers — dMAP (CCS '25)
fingerprints DPI devices by how they resolve protocol ambiguities. The
same primitive inverted lets a censor fingerprint *us* by how our arms
resolve ambiguities. Transparent-mode rotation must stay behind this
bar.

## Acceptance criteria

- [ ] dMAP-style probe sequences replayed against `seg_pre_sni`,
    `seg_mid_sni`, `seg_post_sni`, `rec_pre_sni`, `rec_mid_sni`,
    `two_phase_send`.
- [ ] Verdict per arm: stable ambiguity profile? if yes, which invariant.
- [ ] Recommendation on neighborhood widening or arm retirement where a
    stable profile is found.
- [ ] Result added as a recurring regression in
    [[Epic - Orchestration test posture]] follow-up if material.

## Links

- [[Epic - Semantic TLS first-flight family engine]]
- [[Guard transparent mode against ClientHello byte mutation]]
- [[Rotate successful family through variant neighborhood]]
- [[ripdpi-android-research-2026-04-20]]
