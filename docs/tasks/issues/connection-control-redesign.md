---
id: UIX-1791532861060606
title: Make connection controls clear and accessible
kind: feature
status: blocked
area: ui
priority: high
owner: Connection UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: connection-control-redesign
created: 2026-10-09
updated: 2026-10-09
status_detail: Mobbin MCP is not connected. Reference research must finish before implementation.
---

## Goal

Make the home connection control clear, accessible, and honest about connection evidence. Use Mobbin MCP references before choosing the final layout.

## Acceptance criteria

- Record the Mobbin MCP screen links and explain the patterns used in the final design.
- Show one clear primary action and a separate read-only route summary in each connection state.
- Keep activation, cancellation, retry, and disconnect confirmation visible and reachable by touch, keyboard, and accessibility services.
- Do not show timer-driven connection stages as completed measurements.
- Verify compact widths, large fonts, RTL, light and dark themes, and lockdown mode.
- Run component behavior tests, locale lint, and visual inspection. Record all unavailable gates.

## Ownership

Connection UI owns the component, its UI model and resolver, related tests and previews, and the actuator resource family in all locales. Locale files have one writer. Service, native, and wire contracts are outside this change.

## Research gate

Mobbin MCP tools are absent in the current session. Plugin discovery returned no Mobbin plugin. Public Mobbin pages did not provide the requested connection screens. No Mobbin benchmark findings or UI implementation are claimed.
