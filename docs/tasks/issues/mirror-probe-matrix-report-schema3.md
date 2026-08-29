---
id: DAT-1787994690722107
title: Mirror probe matrix report schema 3
kind: chore
status: done
area: data
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: mirror-probe-matrix-report-schema3
created: 2026-08-29
updated: 2026-08-29
closed_at: "2026-08-29T12:38:48Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Protected PR 460 merged as ec7f670cdd97277d468496338dafbe3eb69ddefb; exact-main CI 33247910603 passed 44 jobs with 17 expected skips; CodeQL 33247910600, Secret Scan 33247910597, and fleet-fixtures 33247910592 passed; schema SHA-256 remains 1504d756decd4de5f13dc468d9a56ffa6bfbef9fd89051a2a0f76a15acee029a.
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
- [x] The exact client commit passes required hosted CI before protected-main
  integration.

## Scope

- In scope: one vendored JSON Schema mirror and the task/OpenSpec evidence
  required to deliver it.
- Out of scope: Kotlin/Rust runtime consumers, report generation, schema 2
  compatibility behavior, and network-exposure schemas.
