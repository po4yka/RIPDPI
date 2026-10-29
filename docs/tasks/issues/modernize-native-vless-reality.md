---
id: TRN-1793300518162274
title: Modernize native VLESS REALITY compatibility
kind: feature
status: doing
area: transport
priority: high
owner: VLESS compatibility package
parent: null
blocked_by: []
spec_mode: required
openspec_change: modernize-native-vless-reality
created: 2026-10-29
updated: 2026-10-29
---

## Goal

Implement real Xray interop, modern lossless native VLESS import, and shared capability admission. The user authorizes separate improvement commits directly on main and pushes to origin.

## Acceptance criteria

- Observe real pinned Xray interop for the native client, including rejection and bounded failure.
- Accept equivalent modern and legacy input and reject conflicting or unsupported options.
- Preserve stored profiles, credentials, group membership, and active settings after rejected imports or refreshes.
- Apply one native VLESS capability policy across import, editing, persistence, and service activation.
- Observe regression tests, affected gates, and independent reviews; commit and push each improvement separately.

## Ownership

- Primary agent alone writes main, task records, and shared capability integration.
- Isolated import worker owns parser and subscription preservation changes.
- Isolated interop worker owns VLESS upstream tests and existing runner integration.
- Workers prepare scoped patches; only the primary agent commits and pushes on main. No shared schema, dependency, locale, or baseline edits.
