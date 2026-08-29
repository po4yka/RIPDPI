---
id: DAT-1787994690722107
title: Mirror probe matrix report schema 3
kind: chore
status: doing
area: data
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: mirror-probe-matrix-report-schema3
created: 2026-08-29
updated: 2026-08-29
---

## Goal

Keep RIPDPI's vendored probe-matrix report contract byte-identical to the
schema 3 contract published by `ripdpi-vpn-deploy`, so client-side contract
checks reject stale or incompatible evidence shapes.

## Acceptance criteria

- [x] The vendored `probe-matrix-report.schema.json` is byte-for-byte equal to
  the producer contract at frozen source
  `po4yka/ripdpi-vpn-deploy@ef688f2a785173913e6e22c42a4843f1c97451bb`.
- [x] JSON validity and the complete contract-mirror test lane pass on the
  exact client commit.
- [x] Task/OpenSpec and architecture-health checks pass without changing
  client runtime behavior, schema 2 window semantics, or network-exposure
  contracts.
- [ ] The exact client commit passes required hosted CI before protected-main
  integration.

## Scope

- In scope: one vendored JSON Schema mirror and the task/OpenSpec evidence
  required to deliver it.
- Out of scope: Kotlin/Rust runtime consumers, report generation, schema 2
  compatibility behavior, and network-exposure schemas.
