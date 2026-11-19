---
id: DGN-1795085357320979
title: Vendor field analytics contracts and unify the failure-stage taxonomy
kind: feature
status: backlog
area: diagnostics
priority: high
owner: Diagnostics engineer
parent: EPC-1795085162356362
blocked_by: []
spec_mode: required
openspec_change: dgn-1795085357320979-vendor-field-contracts-failure-stage-taxonomy
created: 2026-11-19
status_detail: External dependency on ripdpi-vpn-deploy D1 contracts for the final client-field-report v1 schema; local work uses provisional fixtures.
updated: 2026-11-19
---

## Goal

One failure_stage enum shared by Rust and Kotlin, mapped from DpiProbeError, ProbeStage, and connection paths; no text-match classification; no silent error loss on the diagnostics export path. Counterpart: deploy D1 contracts (external dependency, final schema pending there; local fixtures provisional).

## Acceptance criteria

- Contract tests pass against vendored client-field-report v1 fixtures with a drift pin.
- classifyFailureText is removed and all call sites use typed stages.
- Export failures are logged and typed.
