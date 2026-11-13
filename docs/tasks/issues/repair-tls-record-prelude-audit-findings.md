---
id: RST-1794557076132326
title: Repair TLS record prelude audit findings
kind: bug
status: doing
area: rust-native
priority: high
owner: dpi10-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi10-tls-record-repairs
created: 2026-11-13
updated: 2026-11-13
---

## Goal

Correct adaptive record offsets, bound prelude work, and preserve ClientHello bytes across TLS records.

## Acceptance criteria

- Adaptive offsets use payload coordinates once and tests require an applied split.
- Excessive repeat work fails before mutation; valid repeat and skip behavior stays intact.
- Complete multi-record ClientHello inputs retain all handshake and trailing record bytes.
- Split SNI does not hide ECH from activation gates.
- Each finding has a separate implementation commit, local tests, and independent review.
- Required source gates pass before fast-forward integration and push.

## Ownership

- dpi10-offset-worker: F1/F3; tls_prelude.rs adaptive callers and tests/plan_tcp.rs, separate commits.
- dpi10-budget-worker: F2; prelude repeat budget and owning tests, separate worktree.
- dpi10-multirecord-worker: G1; private prelude ingestion, activation metadata, owning tests and docs, separate worktree.
- dpi10-integration: planning artifacts, serialized patch integration, validation, task state and main push.
- Shared tls_prelude.rs is integrated in F1, F2, G1 order. Workers edit isolated worktrees only.
- Cargo.lock, public APIs, schemas, fixtures and baselines are not assigned for edits.
