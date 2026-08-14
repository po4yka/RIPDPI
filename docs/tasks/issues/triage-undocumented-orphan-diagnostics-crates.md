---
id: RST-1786264762917099
title: Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates
kind: feature
status: backlog
area: rust-native
priority: low
owner: unassigned
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917099-triage-undocumented-orphan-diagnostics-crates
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 architecture audit flagged diagnostics prune candidates. Re-verified 2026-06-11 against `docs/architecture/NATIVE_RUST.md` and the workspace `Cargo.toml`s — the earlier "undocumented orphan" framing was inaccurate and is corrected here:

- **`ripdpi-diagnostics-parsers`** — genuine no-runtime-consumer prune candidate. It IS documented (`NATIVE_RUST.md:260`, "Prune candidate unless parser extraction is revived"; also listed at `:435`). The open work is the verdict + metadata, not adding it to the table.
- **`ripdpi-diagnostics-probes`** — NOT an orphan. It is documented as **Keep** (`NATIVE_RUST.md:262`), has real runtime consumers (`ripdpi-diagnostics-runner`, `feature-contract-harness`), and is non-empty (many probe modules + a 185-line `lib.rs`). Drop it from any prune list. (The earlier "empty scaffold / no consumers / not in NATIVE_RUST.md" claim was wrong.)
- **Known prune candidates still present** — `ripdpi-routing`, `ripdpi-diagnostics-net`, `ripdpi-runtime-dns-cache` (one consumer: `ripdpi-ws-bootstrap`), `ripdpi-protocol-detect`, `ripdpi-protocol-loopback`.

Each unreferenced crate adds compile time and obscures the live architecture.

## Proposed change

1. For `ripdpi-diagnostics-parsers` (the only genuine no-consumer crate of the two): decide — (a) delete if dead, or (b) keep with an explicit verdict ("scaffold for planned feature X") recorded under `[workspace.metadata.ripdpi] planned-crates` so the intent is visible. `ripdpi-diagnostics-probes` needs no action — it is a documented Keep with live consumers.
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
