---
id: RST-1790431269626912
title: Preserve Lua state across active tunnel flows
kind: bug
status: review
area: rust-native
priority: high
owner: Native strategy
parent: null
blocked_by: []
spec_mode: required
openspec_change: preserve-lua-active-flow-state
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Keep Lua connection state for every admitted active flow when more than 1024 flows coexist, and reclaim state when a flow ends.

## Acceptance criteria

- A new flow at the state limit cannot evict an existing flow; the configured `on_fail` policy handles the capacity error.
- The TUN integration releases Lua state on observed flow termination and allows a later flow to use the freed slot.
- Integration tests exercise more than 1024 distinct flows, state preservation, and slot reuse.
- Affected Rust tests, API snapshots if changed, and architecture gates pass.

## Ownership

One writer owns Lua state retention, registry and TUN lifecycle integration, tests, and any Rust API snapshot. Review agents are read-only. API snapshots are serialized shared-file edits.
