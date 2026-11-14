---
id: ENG-1794637887235609
title: Fix DPI-20 resource limits and QUIC response contracts
kind: bug
status: doing
area: engine
priority: high
owner: DPI-20 integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi20-remediation
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Bound DPI-20 work, preserve its selected version, and keep decoy replies separate from real-flow success.

## Acceptance criteria

- Reject per-step and aggregate DPI-20 counts above 64 in Kotlin and Rust configuration. Bound direct planner allocation to 64 datagrams of at most 65535 bytes.
- Preserve default and custom fake versions in the selected UDP group and captured v1/v2 output.
- Decoy-only VN replies do not mark the real flow answered or disable its timeout; matched replies and generic UDP keep working.
- Observe regression tests, affected crate tests, Kotlin model tests, independent review, and required integration gates.

## Parallel ownership

- F1 worker: Kotlin StrategyChainValidation and owning tests; Rust proxy-config convert/chain/udp.rs and count tests; desync plan_udp and tamper_version resource guards and owning tests.
- F2 worker: proxy-config convert/protocol.rs and tests/quic_fake_version_config.rs; proxy-runtime tests/wire_e2e.rs; docs/architecture/quic-initial-packetizer.md.
- F3 worker: proxy-runtime runtime/udp state, session, pump, feedback and owning tests.
- Integrator: this portfolio record, OpenSpec artifacts, combined validation, review, commits, and main integration.
- Serialized lanes: no worker changes dependencies, Cargo.lock, schemas, public API snapshots, baselines, locales or golden fixtures. Integrator owns task board generation. Each writer uses a separate worktree.
