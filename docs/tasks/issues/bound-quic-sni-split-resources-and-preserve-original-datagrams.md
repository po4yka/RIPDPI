---
id: ENG-1794637894202698
title: Bound QUIC SNI split resources and preserve original datagrams
kind: bug
status: doing
area: engine
priority: high
owner: dpi18-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi18-bounded-quic-prelude
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Reject excessive QUIC SNI split counts before activation and bound planner work. Preserve the original UDP payload when an optional QUIC prelude exceeds its size budget or its send fails.

## Acceptance criteria

- Kotlin and native configuration reject excessive per-step and aggregate DPI-18 counts.
- Runtime models cannot allocate unbounded DPI-18 packet copies.
- Oversized split preludes do not prevent delivery of the unchanged original.
- Targeted regression tests, source gates, and independent review pass.
- Each fix has a separate commit; integration and push use the tested main lineage.

## Ownership

- bounds worker: isolated `/tmp/ripdpi-dpi18-bounds-20261114`; Kotlin model/config validation and owning tests; Rust config, proxy-config and `ripdpi-desync/src/plan_udp.rs` plus dedicated budget regression tests. No writes to split.rs or UDP emitter.
- datagram worker: isolated `/tmp/ripdpi-dpi18-datagram-20261114`; split packet sizing and `ripdpi-proxy-runtime-desync-adapter/src/udp_desync.rs` with owning tests. No config validation or resource-budget edits.
- integration owner: this task, OpenSpec, generated API snapshots, combined gates, review, commits, main integration and push.
- hypothesis reviewer: read-only source review; no writes.
- Serialized lanes: Cargo.lock and dependency manifests are unchanged. Only integration owner may regenerate API snapshots. No golden or baseline blessing. Shared plan_udp test file belongs to bounds worker; datagram tests use a separate owning module.
