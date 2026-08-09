---
id: RST-1786264762917099
title: Remove unconsumed protocol-detect and diagnostics-parsers crates
kind: chore
status: backlog
area: rust-native
priority: low
owner: Native architecture maintainer
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917099-triage-undocumented-orphan-diagnostics-crates
created: 2026-06-10
updated: 2026-08-09
source_wiki_pages: []
linked_task: null
---

## Motivation

Current locked dependency graphs show exactly two root-only crates with no runtime or dev consumer: `ripdpi-protocol-detect` and `ripdpi-diagnostics-parsers`. Earlier candidates are resolved or live: `ripdpi-routing` and `ripdpi-diagnostics-net` were deleted, `ripdpi-protocol-loopback` is used by Hysteria2 tests, and `ripdpi-runtime-dns-cache` is consumed by `ripdpi-ws-bootstrap`.

## Proposed change

Remove the two unconsumed crates, update workspace manifests/lock and native architecture documentation, and verify that no feature, test, harness, or build references them.

## Acceptance criteria

- [ ] `ripdpi-protocol-detect` and `ripdpi-diagnostics-parsers` are absent from the workspace and locked graph.
- [ ] Repository references and `NATIVE_RUST.md` are updated without deleting any consumed crate.
- [ ] `cargo metadata --locked`, native architecture contracts, and `cargo deny check` pass after the serialized lockfile change.

## Risks / open questions

- Recheck reverse dependencies on the implementation branch immediately before removal; if a real consumer has landed, update or drop this task instead of deleting the crate.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 11; 2026-05-16 gap list item 1).
- `docs/architecture/NATIVE_RUST.md` (crate taxonomy, prune candidates).
