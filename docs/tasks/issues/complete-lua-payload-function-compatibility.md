---
id: RST-1790672261516782
title: Complete zapret2 Lua compatibility
kind: feature
status: doing
area: rust-native
priority: high
owner: Native strategy
parent: null
blocked_by: []
spec_mode: required
openspec_change: lua-payload-function-compatibility
created: 2026-09-29
updated: 2026-09-29
---

## Goal

Provide full Lua and packet-runtime parity with pinned zapret2 through a dedicated opt-in root nfqws2 backend. Preserve safe Android non-root operation. The user approved netfilter production dependencies and main integration/push.

## Acceptance criteria

1. The complete pinned native ABI and all six upstream scripts execute through the root backend, including timers, conntrack, replay, and execution-plan contracts.
2. Android packages the executable for all four ABIs with source/license notices and 16 KiB alignment.
3. Service lifecycle owns activation, process supervision, queue rules, and idempotent stop/failure cleanup. Missing root/NFQUEUE preserves the non-root path.
4. Packet captures and upstream tests verify behavior. Payload-helper regressions cover positions, multi-MSS segmentation, HTTP rewriting, flow isolation, and unsupported operations.
5. Relevant gates, material gaps, reviewed commits, main integration, and remote publication are recorded.

## Ownership

Primary owns strategy-lua/protocol-detect, service activation, configuration, Cargo.lock, documentation, and task artifacts in lua-nonroot. build_tests owns native/zapret2, scripts/native/build-nfqws2, and the nfqws packaging task in nfqws-native-build. The root lifecycle writer owns only root-helper and root-helper-protocol in nfqws-root-lifecycle. Review agents remain read-only. Cargo.lock, protobuf, locale, and golden lanes stay with primary; no baseline or golden update is authorized.
