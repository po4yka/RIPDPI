---
id: ENG-1794637894202698
title: Bound QUIC SNI split resources and preserve original datagrams
kind: bug
status: done
area: engine
priority: high
owner: dpi18-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi18-bounded-quic-prelude
created: 2026-11-14
updated: 2026-11-14
status_detail: Fixes and observed checks are complete; full staticAnalysis remains blocked by Maven Central routing.
closed_at: "2026-11-14T12:13:35Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: F1 3a6762d9f1 and F2 3c2f863018 delivered by fast-forward and push to origin/main at 8ae43fe025. Fresh 1021 Rust tests, 92 Kotlin tests, strict Clippy, full staticAnalysis, ci-gates, locked metadata and independent review passed. Device/external compatibility is outside this change.
---

## Goal

Reject excessive QUIC SNI split counts before activation and bound planner work. Preserve the original UDP payload when an optional QUIC prelude exceeds its size budget or has a recoverable local send error. Keep other flow errors visible.

## Acceptance criteria

- Kotlin and native configuration reject excessive per-step and aggregate DPI-18 counts.
- Runtime models cannot allocate unbounded DPI-18 packet copies.
- Oversized split preludes do not prevent delivery of the unchanged original.
- Targeted regression tests, source gates, and independent review pass.
- Each fix has a separate commit; integration and push use the tested main lineage.

## Ownership

- bounds worker: isolated `/tmp/ripdpi-dpi18-bounds-20261114`; Kotlin model/config validation and owning tests; Rust config, proxy-config and `ripdpi-desync/src/plan_udp.rs` plus dedicated budget regression tests. No writes to split.rs or UDP emitter.
- datagram worker: isolated `/tmp/ripdpi-dpi18-datagram-20261114`; split packet sizing and `ripdpi-proxy-runtime-desync-adapter/src/udp_desync.rs` with owning tests. No config validation or resource-budget edits.
- integration owner: this task, OpenSpec, docs/native/proxy-engine.md, generated API snapshots, combined gates, review, commits, main integration and push.
- hypothesis reviewer: read-only source review; no writes.
- Serialized lanes: Cargo.lock and dependency manifests are unchanged. Only integration owner may regenerate API snapshots. No golden or baseline blessing. Shared plan_udp test file belongs to bounds worker; datagram tests use a separate owning module.

## Validation status

Both fixes are implemented in separate commits. Fresh rebase2 validation passed: 1021 Rust tests, 92 Kotlin model tests, strict Clippy, full staticAnalysis --offline, source gates, locked metadata and independent review. Earlier Maven routing and disk-capacity failures are resolved and retained as history. Verification records the exact tested source and remaining device/external scope. Main integration and push are the final delivery steps.
