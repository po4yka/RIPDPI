---
id: DAT-1788100001077419
title: Mirror observability and network exposure contracts
kind: chore
status: done
area: data
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: mirror-observability-network-exposure-contracts
created: 2026-08-30
updated: 2026-11-24
closed_at: "2026-11-24T15:53:51Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Frozen producer comparison matched all 30 mirrors at PR head 87b29e902, including the seven owned contracts. Required hosted checks passed in run 33942374912, and PR 494 merged.
---

## Goal

Keep RIPDPI's vendored observability and network-exposure contracts
byte-identical to one frozen `ripdpi-vpn-deploy` producer revision so the
deployment contract-sync gate cannot accept missing or independently edited
client mirrors.

## Acceptance criteria

- [x] The seven vendored contract files are byte-for-byte equal to one frozen
  producer commit and are valid JSON.
- [x] The complete client contract-mirror lane, task/OpenSpec validation, and
  architecture checks pass on the exact client commit.
- [x] Required hosted CI passes before protected-main integration.

## Scope

- In scope: five observability schema/example files, two network-exposure
  schemas, and the task/OpenSpec evidence required to deliver their mirrors.
- Out of scope: Kotlin/Rust runtime consumers, telemetry collection, alert
  delivery, firewall activation, and network policy enforcement.
