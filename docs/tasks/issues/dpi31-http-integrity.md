---
id: ENG-1794677657331523
title: Repair DPI-31 HTTP data integrity and mutation receipts
kind: bug
status: review
area: engine
priority: high
owner: HTTP integrity integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi31-http-integrity
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Preserve HTTP request data and expose HTTP mutation outcomes independently of TCP chain execution for DPI-31.

## Acceptance criteria

- Method EOL does not remove meaningful User-Agent bytes or change body bytes. Unsafe inputs remain unchanged.
- Method EOL does not add a second prefix to an already-prefixed request.
- HTTP mutation result is separate from chain disposition and distinguishes absent legacy or unknown evidence, explicitly unchanged or declined bytes, and changed bytes through required receipt consumers.
- Regression tests, an independent read-only review, affected Rust checks, and `just ci-gates` pass. Device and external-network evidence stay separate.

## Ownership and shared-file lanes

- Packet worker: `native/rust/crates/ripdpi-packets/src/http/mutation.rs` and owning HTTP tests, in a separate worktree.
- Receipt worker: `ripdpi-desync`, `ripdpi-desync-runtime`, and required downstream receipt consumers, in a separate worktree.
- Integrator: this portfolio record, OpenSpec artifacts, generated API snapshots, combined validation, and integration.
- Serialize task/OpenSpec and API snapshot writes through the integrator. No Cargo.lock, locale, baseline, golden, dependency, or root-mode change.

## Scope

F1 is meaningful User-Agent loss. H1 is unsafe repeated Method EOL prefixing. F2 is missing independent HTTP mutation observability. H2 is not a confirmed defect: the HTTP inspection cap does not define a universal output or peer limit. Do not add new bypass capabilities or optimize DPI evasion. No external probe, physical-device run, root activation, or real secret is part of acceptance.
