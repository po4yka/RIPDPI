---
id: RLY-1795610464611997
title: Encode chain hops beyond two in the relay wire config
kind: chore
status: backlog
area: relay
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RelayNativeConfig.kt#toResolvedConfig` sends a chain relay through two flat hop slots. A deleted source comment said that hops three and four cannot be encoded and that N-hop composition is the next task.

## Acceptance criteria

A chain with more than two hops reaches the native relay config without loss, or the import path rejects it with a typed error. A test covers a three-hop chain.
