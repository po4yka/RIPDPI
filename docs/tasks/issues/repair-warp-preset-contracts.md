---
id: TRN-1794466458131158
title: Repair WARP preset state and WireGuard compatibility
kind: bug
status: review
area: transport
priority: high
owner: WARP preset repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-warp-preset-contracts
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Repair TUN-04 F1, F2, F3 and the confirmed live-hint refresh defect. Preserve
generic AWG behavior and keep local evidence separate from external WARP proof.

## Acceptance criteria

- Built-in WARP presets authenticate with a plain loopback WireGuard peer.
- Prelude-only WARP preserves backend MACs and the existing reserved-byte path.
- Every selector has one effective enable state across UI and backup boundaries.
- Suggestions are labeled as recommendations and refresh on observed networks.
- Each finding has a separate reviewed fix commit, focused regression evidence,
  and passing combined source/static gates before main integration and push.

## Parallel ownership

- Native coordinator: F1 catalog/asset, warp-core Rust codec, runtime/probe and
  native regressions, plus combined integration and review coordination.
- Settings worker: F2 WarpSettings.kt, AppSettingsJsonWarpMappers.kt,
  WarpSettingsMutationWriter.kt, native preference writer and owning tests.
- Hint worker: F3 WarpSection.kt and its semantic tests; H2 settings dependency,
  assembler/network presentation and callers/tests in a separate commit.
- Coordinator alone: portfolio, OpenSpec, board, generated API/architecture files.
- All writers use separate worktrees. Locale resources have one owner: hint worker
  if a change is needed. No dependency, proto, golden, or quality baseline writes.
