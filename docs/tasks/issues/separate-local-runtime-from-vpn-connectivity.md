---
id: SVC-1786597927063162
title: Separate local runtime readiness from VPN connectivity
kind: bug
status: review
area: service
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: separate-runtime-vpn-health
created: 2026-08-13
updated: 2026-08-13
related_tasks: []
status_detail: Typed VPN data-plane projection and lifecycle-only system wording implemented with observed RED/GREEN tests; affected unit, full/simple lint, and staticAnalysis gates pass. Physical-device and hosted-CI evidence remain pending.
---

## Goal

Keep service lifecycle readiness and verified VPN data-plane connectivity as separate user-visible states.

## Acceptance criteria

- [x] A running local VPN runtime does not by itself produce a positive VPN-working presentation.
- [x] Captured Android path evidence distinguishes validated VPN connectivity from pending or failed validation.
- [x] Proxy mode and unavailable path evidence do not make unsupported VPN health claims.
- [x] Regression tests cover validated, pending, failed, and non-VPN projections.
