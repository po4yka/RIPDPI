---
id: ENG-1794722736729815
title: Correct DPI-38 failure decisions and feedback
kind: bug
status: review
area: engine
priority: high
owner: DPI-38 integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi38-failure-contracts
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Correct the nine reproduced DPI-38 audit findings and the local-cancellation defect found during focused review. Preserve unrelated work.

## Acceptance criteria

- Each F1-F10 has an observable regression test and a separate fix commit.
- Complete affected Rust tests, lint and offline CI gates without weakening baselines.
- Independent read-only review passes before integration.
- Integrate into main and push only validated commits, as explicitly requested by the user.

## Parallel ownership

- The cancellation writer owns only `ripdpi-proxy-runtime/src/runtime/relay/stream_copy/copy_halves.rs`, `mod.rs` and `client_cancel_tests.rs`, plus a dedicated local-cancellation test module if needed. The response writer retains `observers.rs` and TLS/HTTP inspection. Changes to a shared path require an explicit handoff before writing.

- After the cancellation component is frozen, the response writer owns the F4 pending-reader integration in `relay/pending_response.rs`, `relay/session.rs`, `relay/pending_response/neutral_tests.rs` and the `stream_copy/mod.rs` pending-call site. This handoff transfers `mod.rs`; `copy_halves.rs` and `client_cancel_tests.rs` stay with the completed cancellation slice. The reader observes revoked first-response ownership, never ordinary `peer_done` as proof of cancellation.

- F4 also owns the mechanical `Pending.finish` caller update in `stream_copy/continuation_tests.rs`. If the full suite demonstrates the old first-response FIN expectation is wrong under the new causal contract, the response writer may update only that original assertion in `client_cancel_tests.rs`; the new F10 controls and production cancellation paths stay unchanged.

- The HTTP classifier writer owns only `ripdpi-failure-classifier/src/block_detection/http_classification.rs`, `http_parse.rs` and `tests.rs`, plus a dedicated HTTP test module if the existing test file needs a split. This assignment overrides the general response-classifier lane below for these paths. The response writer keeps TLS and all runtime observation integration. Integration combines the classifier and runtime changes into F4.

- Integration owner: planning, task state, F2 connection retry budget, combined validation, review, integration and push.
- Evolution writer: F1 and F7; adaptive/strategy services and decision ports, evolution attempt propagation through desync and TCP/UDP runtime. Do not change response inspection logic or route retry policy.
- Response writer: F4, F5, F6, F9; failure-classifier, response policy, response boundary tracking, first exchange, first-outbound response and stream-copy observers. Coordinate session fields with the evolution writer.
- Reprobe writer: F3 and F8; runtime/reprobe and proxy-runtime-adapter/failure.rs. Do not change response classifier or adaptive feedback contracts.
- All writers use separate worktrees. The integration owner serializes merges. Session-field and runtime re-export overlaps are resolved by the integration owner.
- Cargo.lock, dependency manifests, protobuf, wire registries, locale sets, API snapshots, generated reports and baselines have one owner: integration. Changes need a concrete check; baselines must not hide regressions.

## Evidence

The initial audit is /Users/npochaev/Downloads/RIPDPI_network_audits/DPI-38.md. It records local loopback and state reproductions on main 4b5f1cdfd89a5b029b7e69b757d94c815c84147d.
