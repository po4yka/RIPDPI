---
id: DAT-1788100001077419
title: Mirror observability and network exposure contracts
kind: chore
status: doing
area: data
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: mirror-observability-network-exposure-contracts
created: 2026-08-30
updated: 2026-09-05
---

## Goal

Keep RIPDPI's vendored observability and network-exposure contracts
byte-identical to one frozen `ripdpi-vpn-deploy` producer revision so the
deployment contract-sync gate cannot accept missing or independently edited
client mirrors.

## Acceptance criteria

- [ ] The seven vendored contract files are byte-for-byte equal to one frozen
  producer commit and are valid JSON.
- [ ] The complete client contract-mirror lane, task/OpenSpec validation, and
  architecture checks pass on the exact client commit.
- [ ] Required hosted CI passes before protected-main integration.

## Scope

- In scope: five observability schema/example files, two network-exposure
  schemas, and the task/OpenSpec evidence required to deliver their mirrors.
- Out of scope: Kotlin/Rust runtime consumers, telemetry collection, alert
  delivery, firewall activation, and network policy enforcement.
