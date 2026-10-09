---
id: UIX-1791532861060606
title: Make connection controls clear and accessible
kind: feature
status: review
area: ui
priority: high
owner: Connection UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: connection-control-redesign
created: 2026-10-09
updated: 2026-10-09
status_detail: The Mobbin-based UI redesign passed 122 local tests, app/service lint, and staticAnalysis. Eight SDK 36 Compose renders were inspected and independent review is complete. Target SDK 37 preview, real native APK, device acceptance, hosted CI, and golden acceptance remain unverified or blocked.
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

## Research evidence

Mobbin MCP research completed on 2026-10-09. The inspected references are [NordVPN widgets](https://mobbin.com/screens/8b54292c-d164-4c53-a5c4-df5ed16853e5) and the [Opera VPN panel](https://mobbin.com/screens/d535f3b2-da66-427c-92d7-b679c7dede17). See the linked OpenSpec design for adopted patterns and the RDS rail deviation.
