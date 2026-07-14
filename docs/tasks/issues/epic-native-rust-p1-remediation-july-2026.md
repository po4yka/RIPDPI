---
title: Fix July 2026 native Rust P1 audit findings
type: epic
status: doing
area: epic
priority: critical
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-14
updated: 2026-07-14
---

## Goal

Eliminate every confirmed P1 defect from the July 2026 repeated native Rust audit with one regression-backed atomic commit per defect.

## Why now

The findings include a Reality authentication fail-open, transport-wide head-of-line blocking and cancellation corruption, unbounded VPN memory paths, stale MapDNS and UID-attribution lifecycles, and an unbounded native HTTP response body. The post-fix native branch also needs a durable regression contract that prevents the current `main` diagnostics lifecycle regression from returning during integration.

## Key decisions

- Build on clean post-fix baseline `f5cbffd68` because it contains the previously accepted native fixes audited for this series.
- Keep every fix and its regression tests in a separate Conventional Commit.
- Preserve nonblocking diagnostics report polling and a bounded concurrent worker reaper when the branch is later reconciled with `main`.
- Do not integrate, delete, or push the worktree branch without explicit user confirmation.

## Scope

- [ ] Fail closed when the Reality client-hello callback was never invoked.
- [ ] Lock the bounded, nonblocking diagnostics lifecycle contract against the current `main` regression.
- [ ] Bound production TCP sessions and enforce TCP connect/read-write deadlines.
- [ ] Bound the TUN transmit queue by packets and bytes.
- [ ] Replace MapDNS boolean pins with per-flow mapping leases and fail on fully pinned exhaustion.
- [ ] Make flow-attribution cleanup exact-tuple and generation safe across flow reuse.
- [ ] Prevent a slow Mieru stream from blocking the carrier demultiplexer.
- [ ] Make Mieru frame writes and open registration cancellation safe.
- [ ] Add AnyTLS stream FIN/deregistration and break the session ownership cycle.
- [ ] Supervise AnyTLS reader and writer failures as one session lifecycle.
- [ ] Enforce a bounded native HTTP response body before base64 encoding.

## Ship definition

- Eleven atomic fix commits exist, each with a regression test that fails against the audited behavior.
- Focused crate tests and clippy pass after each commit.
- Workspace formatting, clippy, nextest, dependency policy, architecture health, and native CI guards pass on the combined branch.
- Android/Linux-only paths are cross-checked where the local toolchain supports them; any remaining device/kernel verification debt is reported explicitly.

## Work log

- 2026-07-14: Goal started in `worktree-fix-native-p1-audit` from `f5cbffd68`; ownership assigned to Codex.
