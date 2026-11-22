---
id: DGN-1795338086230261
title: Compare a working direct target through the active VPN
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: home-working-path-comparison
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Detect when a target with a complete HTTP response on the direct path fails over the active proxy route.

## Acceptance criteria

- Include one working non-control domain within the existing comparison target cap.
- Use complete RAW reports and require successful HTTP evidence for controls.
- Report a regression when the same HTTP exchange fails in path while TLS still succeeds.
- Exercise the Home workflow and pass the diagnostics checks.
