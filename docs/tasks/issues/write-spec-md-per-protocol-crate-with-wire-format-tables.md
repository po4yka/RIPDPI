---
title: Write SPEC.md per protocol crate with wire-format tables
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: [add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Write SPEC.md per protocol crate with wire-format tables #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `write-spec-md-per-protocol-crate-with-wire-format-tables`
- **Verify:** `scripts/ci/verify_spec_md_present.sh`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `native/rust/crates/ripdpi-xhttp/**`, `native/rust/crates/ripdpi-hysteria2/**`, `native/rust/crates/ripdpi-tuic/**`, `native/rust/crates/ripdpi-shadowtls/**`, `native/rust/crates/ripdpi-naiveproxy/**`, `native/rust/crates/ripdpi-masque/**`, `native/rust/crates/ripdpi-ws-tunnel/**`, `scripts/ci/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** `add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Promote the wire-format comments scattered through protocol crate
sources (e.g. `vless/wire.rs` already has a partial ASCII layout)
into a dedicated `SPEC.md` at the root of each protocol crate, with
byte-level tables, RFC/upstream citations, and a clear distinction
between mandatory and optional fields.

## Context

A reader today reconstructs each wire format by reading source code
plus partial comments. With `SPEC_VERSION.md` pinning the upstream
tag, `SPEC.md` defines what RIPDPI's implementation actually
encodes, so divergence from upstream is reviewable as a diff.

## Acceptance criteria

- [ ] Each of the eight protocol crates has a top-level `SPEC.md`.
- [ ] Each `SPEC.md` includes: scope, upstream citation, byte-level
    layout table (or RFC reference), known-divergences-from-upstream
    section, and a non-goals section.
- [ ] A `scripts/ci/verify_spec_md_present.sh` check fails if a
    protocol crate is missing `SPEC.md` or if `SPEC.md` has no
    "Upstream:" line.

## Definition of done

- All eight crates pass the presence check.
- Each `SPEC.md` is linked from the crate's `lib.rs` module doc.

## Links

- [[add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]]
- [[tag-protocol-contract-fixtures-by-upstream-version]]
