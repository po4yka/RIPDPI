---
id: DAT-1788656601400373
title: Mirror real VPS AWG NAT evidence schema 4
kind: chore
status: doing
area: data
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: mirror-real-vps-awg-nat-evidence-schema4
created: 2026-09-06
updated: 2026-09-06
---

## Goal

Keep RIPDPI's vendored real-VPS AWG NAT evidence contract byte-identical to
`po4yka/ripdpi-vpn-deploy@c8ad0861711eb5fb63c6fad46c28c179678d51a5`
without adding client runtime, signer, relay, device, or deployment behavior.

## Acceptance criteria

- [ ] The vendored evidence schema is byte-for-byte equal to the frozen producer
  file, parses as JSON, and declares `real_vps_awg_nat_evidence_v4`.
- [ ] Focused mirror checks, task/OpenSpec validation, and architecture health
  pass on the exact client commit without Kotlin or Rust runtime changes.
- [ ] Required hosted CI passes before protected-main integration.

## Ownership

This task exclusively owns
`core/data/src/test/resources/contract/real-vps-awg-nat-evidence.schema.json`
and its task/OpenSpec records. The producer implementation, signer, relay,
client runtime, devices, artifacts, deployment, and shared `main` are out of
scope.
