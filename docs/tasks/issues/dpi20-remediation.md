---
id: ENG-1794637887235609
title: Fix DPI-20 resource limits and QUIC response contracts
kind: bug
status: done
area: engine
priority: high
owner: DPI-20 integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi20-remediation
created: 2026-11-14
updated: 2026-11-14
closed_at: "2026-11-14T12:33:41Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Three P2 corrections are committed on main and pushed at 05f91ba333. Rust 1589 passed; explicit wire 9/9; model 94/0; strict Clippy, model Detekt/lint, ci-gates, architecture, locked metadata and task contracts passed. Separate review found no actionable defects. Physical/external/hosted acceptance remains unclaimed.
---

## Goal

Bound DPI-20 work, preserve its selected version, and keep decoy replies separate from real-flow success.

## Acceptance criteria

- Reject per-step and aggregate DPI-20 counts above 64 in Kotlin and Rust configuration. Bound direct planner allocation to 64 datagrams of at most 65535 bytes.
- Preserve default and custom fake versions in the selected UDP group and captured v1/v2 output.
- Decoy-only VN replies do not mark the real flow answered or disable its timeout; matched replies and generic UDP keep working.
- Observe regression tests, affected crate tests, Kotlin model tests, independent review, and required integration gates.

## Parallel ownership

- F1 worker: Kotlin StrategyChainValidation and owning tests; Rust config CLI and proxy-config convert/chain/udp.rs and count tests; desync plan_udp and tamper_version resource guards and owning tests.
- F2 worker: proxy-config convert/protocol.rs and tests/quic_fake_version_config.rs; proxy-runtime tests/wire_e2e.rs; docs/architecture/quic-initial-packetizer.md.
- F3 worker and integrator: proxy-runtime runtime/udp state, session, pump, feedback and owning tests.
- Integrator: this portfolio record, OpenSpec artifacts, combined validation, review, commits, and main integration.
- Serialized lanes: no worker changes dependencies, Cargo.lock, schemas, existing public API snapshots, baselines, locales or golden fixtures. Integrator owns task board generation. Each writer uses a separate worktree.
