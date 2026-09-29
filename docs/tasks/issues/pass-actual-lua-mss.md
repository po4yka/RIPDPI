---
id: RST-1790682603953151
title: Pass actual sending MSS to Lua strategies
kind: feature
status: doing
area: rust-native
priority: high
owner: Native strategy
parent: null
blocked_by: []
spec_mode: required
openspec_change: actual-lua-mss
created: 2026-09-29
updated: 2026-09-29
---

## Goal

Pass the current effective TCP send MSS from the physical socket to non-root Lua strategies.

## Acceptance criteria

- Read snd_mss on every TCP Lua invocation; use 1460 only when no valid measurement exists.
- Bundled tcpseg preserves ordered payload and respects measured MSS.
- UDP and unmeasured TUN contexts do not invent a measured MSS.
- Rust gates, Android socket execution, independent review, main integration and remote publication pass.

## Ownership

The primary writer owns affected Rust sources and this task/specification. Test and review agents are read-only. No serialized production, JNI, protobuf, locale, or baseline files change.
