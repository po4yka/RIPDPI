---
id: PRX-1794211693515272
title: Fix HTTP request parsing and mutation boundaries
kind: bug
status: review
area: proxy
priority: high
owner: HTTP repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-http-request-boundaries
created: 2026-11-09
updated: 2026-11-09
---

## Goal

Repair the five STD-09 findings while preserving opaque stream bytes, non-root operation, and existing transport ownership.

## Acceptance criteria

All five findings have regression tests and separate repair commits. HTTP bodies and HTTP/2 data remain unchanged across read boundaries. Partial heads do not become complete messages. Host metadata follows the request authority. Run relevant Rust checks, independent loopback decoders, review, and offline CI gates before integration.

## Ownership

- Coordinator owns planning, integration, runtime stream eligibility, HTTP assembly, root TUN safety, documentation, and combined validation.
- Parser worker owns ripdpi-packets HTTP parsing/classification tests (F3 then F4 in separate commits), plus the strategy-http separator function for F3.
- Lua worker owns core/engine/src/main/assets/lua/zapret-antidpi.lua http_unixeol and ripdpi-strategy-lua/tests/selected_payload_compat.rs (F2).
- Builtin worker owns ripdpi-strategy-http methodeol and its regression tests (F5).
- Each writer uses a separate worktree. The coordinator serializes cherry-picks to overlapping HTTP files and any Cargo manifests, lockfile, API snapshots, generated exports, and task board. Workers must not edit shared lockfiles, baselines, schemas, task state, or each other's paths.
