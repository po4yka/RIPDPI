---
id: UIX-1791538891055420
title: Repair audited UI interaction and evidence defects
kind: feature
status: doing
area: ui
priority: high
owner: UI integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: audited-ui-quality
created: 2026-10-09
updated: 2026-10-09
---

## Goal

Repair confirmed interaction, accessibility, credential-display, and diagnostic-evidence defects across the app. Preserve service and storage contracts.

## Acceptance criteria

Each repair has a regression check. Changed layouts are inspected at compact width, large text, RTL, and both themes where supported. Run app/service locale lint, static analysis, architecture health, and locked Cargo metadata before integration. Report preview, native, device, golden, and hosted CI limits separately. No quality gate is removed.


## Ownership

- Shared UI writer: `app/.../ui/components/` and shared component tests.
- Setup UI writer: settings, routes, config, profiles, subscriptions, Xray, onboarding, credential screens and their presentation tests.
- Diagnostics UI writer: diagnostics, detection history, strategy tuner, tools, and their presentation tests.
- Integration writer: all locale resources, task records, OpenSpec, connection redesign integration, combined gates, main integration, and push.
- Golden fixtures are serialized and remain unchanged until the user authorizes the exact family. No writer owns build logic, schemas, native code, or another writer's files.
- Each writer uses a separate worktree, preserves other work, and makes scoped atomic commits.
