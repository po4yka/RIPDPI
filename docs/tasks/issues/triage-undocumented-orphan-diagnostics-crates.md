---
title: "Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates"
type: task
status: backlog
area: rust-native
priority: low
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 architecture audit found two **new** undocumented orphan crates and reconfirmed five known prune candidates still lingering:

- **New orphans (not in `NATIVE_RUST.md`)** — `ripdpi-diagnostics-parsers` and `ripdpi-diagnostics-probes`. Both compile into the workspace, have no runtime consumers, and do not appear in the crate-classification table. They are either dead scaffolding or planned work never wired in. (`ripdpi-diagnostics-probes` is the known empty scaffold; `ripdpi-diagnostics-parsers` is newly noticed.)
- **Known prune candidates still present** — `ripdpi-routing`, `ripdpi-diagnostics-net`, `ripdpi-runtime-dns-cache` (one consumer: `ripdpi-ws-bootstrap`), `ripdpi-protocol-detect`, `ripdpi-protocol-loopback`.

Each unreferenced crate adds compile time and obscures the live architecture.

## Proposed change

1. For `ripdpi-diagnostics-parsers` and `ripdpi-diagnostics-probes`: decide per crate — (a) delete if dead, or (b) add to `NATIVE_RUST.md` with an explicit verdict ("scaffold for planned feature X") and, if planned, list under `[workspace.metadata.ripdpi] planned-crates` so the intent is visible.
2. For the five known prune candidates: either delete, or record under `[workspace.metadata.ripdpi] prune-candidates` in the workspace `Cargo.toml`.
3. Add a CI rule that fails if a crate not on the prune/planned list gains a new direct dep on a prune-candidate crate (freezes accidental coupling without forcing immediate deletion).

## Acceptance criteria

- [ ] PR states a verdict for each of the two new orphans and the five prune candidates.
- [ ] `NATIVE_RUST.md` lists every workspace crate (no undocumented crate remains) or the orphan is deleted.
- [ ] `prune-candidates` / `planned-crates` metadata lists exist where crates are kept.
- [ ] CI guard prevents new direct deps on prune-candidate crates.
- [ ] `cargo metadata` + `cargo deny check` clean after any deletions; `Cargo.lock` change is its own reviewed hunk.

## Risks / open questions

- Deleting a crate that a future planned task needs wastes the scaffold — prefer documenting-as-planned over deletion when intent is unclear; confirm against `ROADMAP.md` and open epics.
- `ripdpi-runtime-dns-cache` has one real consumer (`ripdpi-ws-bootstrap`) — it is "prune candidate" only if that consumer is also retired; do not delete blindly.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 11; 2026-05-16 gap list item 1).
- `docs/architecture/NATIVE_RUST.md` (crate taxonomy, prune candidates).
