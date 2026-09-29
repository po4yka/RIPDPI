---
id: RST-1790683922892928
title: Port zapret verification examples and complete full audits
kind: feature
status: review
area: rust-native
priority: high
owner: Native verification
parent: null
blocked_by: []
spec_mode: required
openspec_change: zapret-verification-examples
created: 2026-09-29
updated: 2026-09-29
status_detail: Implementation and local/Android checks passed; main push verified. Required hosted CI remains pending.
---

## Goal

Port deterministic upstream packet examples and make uncapped full_matrix_v1 audits execute every applicable candidate within existing budgets.

## Acceptance criteria

- Upstream-derived IPv4/IPv6, TCP options and checksum vectors verify complete packet and Lua strategy output.
- Uncapped full audits do not skip TCP after confirmed QUIC or eliminate candidates by pilot results.
- Deadline-limited full audits report PartialResults, including DNS fallback. Quick and capped scans keep their current behavior.
- Relevant Rust and Android tests, lint, review and remote publication pass.

## Ownership

Primary owns tunnel interceptor tests, packet-vector oracle, documentation and all task/spec files in lua-nonroot. The isolated audit worker owns only monitor-engine sources/tests in exhaustive-strategy-audit. Cargo.lock, contracts, locales and baselines do not change. Review/test agents are read-only.
