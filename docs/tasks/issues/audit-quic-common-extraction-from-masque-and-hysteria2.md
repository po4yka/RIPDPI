---
title: Audit QUIC-common extraction from MASQUE, Hysteria 2, and TUIC
type: task
status: done
area: rust-native
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [x] #task Audit QUIC-common extraction from MASQUE, Hysteria 2, and TUIC #repo/RIPDPI #area/rust-native #status/done 🔼

## Audit outcome

Recommendation: **do not extract a new crate; tighten the existing
re-export surface instead**. See
`docs/architecture/quic-common-extraction-audit.md`.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `audit-quic-common-extraction-from-masque-and-hysteria2`
- **Verify:** `cargo check --workspace && cargo test -p ripdpi-masque -p ripdpi-hysteria2 -p ripdpi-tuic`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-masque/**`, `native/rust/crates/ripdpi-hysteria2/**`, `native/rust/crates/ripdpi-tuic/**`, `docs/architecture/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Audit whether the QUIC-common bits (UDP socket build, endpoint rebind,
path-validation migration, H3 client parts) currently shared between
`ripdpi-hysteria2`, `ripdpi-masque`, and `ripdpi-tuic` should move into
a neutral `ripdpi-quic-common` crate. Produce a recommendation; do not
yet refactor.

## Context

- `ripdpi-masque/Cargo.toml` depends on `ripdpi-hysteria2`.
- `ripdpi-hysteria2::quic_transport` re-exports `build_client_udp_socket`,
  `build_quic_endpoint`, `maybe_rebind_endpoint`, `H3ClientParts`,
  `H3Transport`, `QuicBiStream`, `QuicDatagramTransport`,
  `QuicTransport`, `QuicTransportConfig`.
- `ripdpi-tuic/src/endpoint.rs` mirrors the same `build_client_udp_socket`
  shape but reimplements it.
- `ripdpi-masque` couples its RFC-9298 path to Hysteria's release
  cadence as a side effect of the dep.

This is an arch-layer-auditor question, not a refactor: the goal is a
written recommendation with cost/benefit, owned by one person.

## Acceptance criteria

- [ ] A `docs/architecture/quic-common-extraction-audit.md` note
    documents:
    - which symbols are shared today
    - which symbols are accidentally shared (would diverge with no
      shared abstraction)
    - the dependency edges between MASQUE / Hysteria 2 / TUIC
    - a recommendation: extract / don't extract / partial extract
    - estimated diff size and migration risk
- [ ] If the recommendation is "extract", a follow-up task slug is
    proposed (do not create the task in this slot).
- [ ] If the recommendation is "don't extract", the doc states the
    de-coupling alternative for MASQUE (e.g. re-export only what is
    needed, no transitive dep).

## Definition of done

- Audit doc exists and is linked from `docs/architecture/README.md`.
- A binding recommendation is recorded with one named owner.

## Risks / open questions

- A premature extraction risks adding a fourth crate-shaped
  abstraction to an area that already has three. Bias toward
  documentation over refactor in this slot.

## Links

- [[Epic - Control-plane hardening]]
- [[relay-masque-status]]
