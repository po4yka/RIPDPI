---
id: VPN-1794152738556618
title: Fix VPN flow owner attribution boundaries
kind: bug
status: doing
area: vpn
priority: high
owner: VPN flow attribution integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn08-flow-owner-attribution
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Fix VPN-08 findings F1 through F5 and fence package-invalidation races without root or destination-based app inference.

## Acceptance criteria

- Ordinary UDP UID age expires despite continuous traffic.
- Same-IP apps and synthetic DNS flows keep independent app identity.
- Late session/package results cannot alter current state.
- Resolver registration precedes TUN packet handling.
- Targeted behavior tests, required combined gates and independent reviews pass; device gaps remain explicit.

## Parallel ownership

Integration owns portfolio/spec records. UDP writer owns attribution expiry until its commit. Startup writer owns engine tunnel lifecycle files. Correlation/session writer owns Store/Bridge, service lifecycle, scope transport, learning and all serialized JNI/telemetry contracts after the UDP handoff. Each writer uses an isolated worktree. No shared dependency, locale, baseline or golden writer is assigned.
