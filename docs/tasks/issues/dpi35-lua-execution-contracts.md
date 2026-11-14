---
id: RST-1794684393149595
title: Repair DPI-35 Lua execution contracts
kind: bug
status: review
area: rust-native
priority: high
owner: Native strategy
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi35-lua-execution-contracts
created: 2026-11-14
updated: 2026-11-15
---

## Goal

Repair the six confirmed DPI-35 audit findings without changing the separate trusted nfqws2 backend.

## Acceptance criteria

1. Sandbox external loaders accept text only; clock_gettime returns seconds and nanoseconds.
2. MODIFY packet results contain the changed payload. Unsupported transport mutations remain rejected.
3. More than 2048 live flows cannot restart an admitted flow or activate a bypassed flow midway.
4. UI-generated bundled Lua configuration lists its library dependencies in order.
5. Invalid TUN Lua configuration fails activation; both owners receive extracted assets.
6. Regression tests, independent review, affected builds, ci-gates and integration checks pass. Each finding has a separate commit.

## Ownership

- Primary owns planning, F2 payload outcome and packet regression files, combined validation, integration and push.
- sandbox_clock owns F1 loader/validation sections and F6 clock section in strategy-lua plus their tests, in its own worktree. Two separate commits.
- flow_lifetime owns F3 state/admission/close sections in strategy-lua, conn_state_persistence.rs and the existing TUN capacity test. One commit.
- ui_activation owns F4 UI dependency handling/tests and F5 TUN rule compilation/constructors/callers, service asset extraction and tests. Two commits.
- Each writer uses its own worktree. Primary serializes integration of strategy-lua and TUN test edits. Cargo.lock, locale files, schemas and baselines are not write lanes for this change.
- pr-reviewer reviews the combined diff read-only before final commits/integration.

## Evidence boundary

The six confirmed findings are the implementation scope. Audit H1-H5 are residual hypotheses, not proof of defects. Device/root/external-network acceptance is not authorized by commit/push permission. Local regression and source gates provide the acceptance evidence for these repairs.
