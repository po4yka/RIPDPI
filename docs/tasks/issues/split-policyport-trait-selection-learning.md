---
id: RST-1786264762917192
title: Split the 12-method PolicyPort trait into selection and learning sub-traits
kind: feature
status: doing
area: rust-native
priority: medium
owner: codex
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917192-split-policyport-trait-selection-learning
created: 2026-06-10
updated: 2026-08-31
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Rust API audit flagged an Interface-Segregation violation. `ripdpi-runtime-decision-ports/src/policy.rs:138` — `PolicyPort` now has 12 methods (threshold 8): `select_initial`, `note_success`, `advance_route`, `note_block_signal`, `supports_trigger`, `select_next`, `store_route`, `clear_connection_cache`, `build_retry_penalties`, `autolearn_state`, `drain_autolearn_events`, `flush_host_store`. Callers that only select routes are forced to depend on (and mock, in tests) the full learning surface.

## Proposed change

Split per the audit's recommendation:
- `PolicySelectionPort` — `select_initial`, `select_next`, `advance_route`, `store_route`, `clear_connection_cache`, `build_retry_penalties`, `supports_trigger`.
- `PolicyLearningPort` — `note_success`, `note_block_signal`, `autolearn_state`, `drain_autolearn_events`, `flush_host_store`.

Keep a blanket/aggregate `PolicyPort: PolicySelectionPort + PolicyLearningPort` for existing impls, or have the concrete type implement both. Update call sites to depend on the narrower trait they need.

## Work ownership (2026-08-31)

- `codex` owns the trait hierarchy and rustdoc contract in
  `ripdpi-runtime-decision-ports/src/policy.rs`, task/OpenSpec lifecycle, API
  snapshot decisions, integration, commits, and push.
- `map_policy_port` and `audit_policy_port_baseline` are read-only and own the
  current caller/API inventory plus independent baseline findings.
- `codex` also owns the focused RED/GREEN compile-contract test plus the
  mechanical implementor and caller migration after the independent audits
  establish the exact boundaries.

## Acceptance criteria

- [ ] Baseline audit confirms the current 12-method `PolicyPort` shape.
- [ ] Two sub-traits exist; selection-only and learning-only callers depend on the narrower one.
- [ ] No behavior change; existing impls satisfy both.
- [ ] Test mocks simplify (selection tests no longer stub learning methods).
- [ ] `cargo nextest run --locked` green for the decision-ports consumers; clippy clean.

## Risks / open questions

- Per `llm-rust-prompts.md`, do not delegate trait-hierarchy design to an LLM unsupervised — write the trait split + doc contract by hand, then delegate the mechanical impl/caller updates.
- Confirm no caller genuinely needs the union at one call site (if so, accept `PolicyPort` aggregate there).

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 13 / PolicyPort ISP).
- `rust-api-auditor` / `rust-discipline` skill (trait design).
