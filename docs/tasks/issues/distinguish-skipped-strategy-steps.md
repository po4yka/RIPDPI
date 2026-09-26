---
id: RST-1790428851570062
title: Distinguish skipped strategy steps from handled no-op
kind: bug
status: review
area: rust-native
priority: high
owner: Native strategy
parent: null
blocked_by: []
spec_mode: required
openspec_change: distinguish-strategy-step-outcomes
created: 2026-09-26
updated: 2026-09-26
status_detail: Local Rust and architecture gates passed; remote CI and device evidence remain blocked.
---

## Goal

Allow a matching strategy to decline a packet so the registry can try the next step, while preserving terminal successful no-op and explicit Lua verdicts.

## Acceptance criteria

- The strategy trait returns an explicit handled or skipped outcome; a skipped step restores the previous plan and continues independently of `on_fail`.
- UDP and IPv6 strategies return skipped when their packet or runtime requirements are absent; action-producing strategies report handled.
- A successful Lua no-op remains terminal, and explicit Lua pass, modify, and drop verdicts keep their existing meanings.
- Registry chain tests cover skip, plan restoration, failure policy, and Lua no-op; affected crate tests and API snapshot checks pass.

## Ownership

One writer owns the strategy trait, registry, implementations, tests, and API snapshot. Review agents are read-only. The trait API snapshot is a serialized shared-file lane.
