---
id: RST-1786264762917430
title: Reduce pub surface of monitor-engine/config and add golden contracts for high-fan-in crates
kind: feature
status: backlog
area: rust-native
priority: low
owner: unassigned
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917430-reduce-pub-surface-monitor-engine-and-config
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Rust API audit flagged visibility bloat and blast-radius risk:

- **`ripdpi-monitor-engine`** — 92 pub items (a new high-water mark). Many internal probe/runner types are `pub(crate)` candidates; the large surface is an unnecessary semver commitment for an internal diagnostics engine.
- **`ripdpi-config`** — 117 pub items, and a naming-confusion risk: it sounds like CLI config but is the shared workspace-wide core config used by 17 crates.
- **High fan-in hubs** — `ripdpi-failure-classifier` (fan-in 18) and `ripdpi-config` (17) have the largest blast radius; any non-backward-compatible API change propagates across the workspace. They lack the golden-contract tests that `ripdpi-diagnostics-contracts` and `ripdpi-proxy-config` already have.

## Proposed change

1. Sweep `ripdpi-monitor-engine` for `pub` items only used within the crate; demote to `pub(crate)`. Keep the externally-consumed surface (verify against `ripdpi-android-diagnostics-adapter` / `ripdpi-monitor-proxy-runtime` usage).
2. Add a doc comment to `ripdpi-config/src/lib.rs` clarifying it is the workspace-wide core config (not CLI-only); optionally sweep its pub surface too.
3. Add golden-contract tests for the public types of `ripdpi-failure-classifier` and `ripdpi-config`, mirroring the approach used for `ripdpi-diagnostics-contracts`/`ripdpi-proxy-config`, so accidental API changes are caught.

## Acceptance criteria

- [ ] `ripdpi-monitor-engine` pub-item count meaningfully reduced; no external consumer breaks.
- [ ] `ripdpi-config` lib.rs documents its true role.
- [ ] Golden-contract tests exist for `ripdpi-failure-classifier` and `ripdpi-config` public surfaces.
- [ ] `cargo nextest run --locked` green workspace-wide; clippy clean.

## Risks / open questions

- Demoting visibility can break a non-obvious external consumer — drive the sweep from actual cross-crate usage, not guesswork.
- Golden contracts add maintenance; scope them to the genuinely-stable public types, not internal helpers.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, items 7, 8, 13).
- Precedent: `ripdpi-diagnostics-contracts` / `ripdpi-proxy-config` golden contracts.
