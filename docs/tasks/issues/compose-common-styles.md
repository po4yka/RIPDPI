---
id: UIX-1792228984398359
title: Migrate RDS components to Compose CommonStyle
kind: feature
status: doing
area: ui
priority: high
owner: styles-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: compose-common-styles
created: 2026-10-17
updated: 2026-10-17
---

## Goal

Use Compose 1.13 CommonStyle for RDS-owned visual styling while keeping the current appearance, interaction, accessibility, and motion contracts.

## Acceptance criteria

- Resolve the published beta BOM with compile SDK 37.1 and compile Full and Simple.
- Apply typed styles to eligible RDS controls, surfaces, and screen recipes; document exceptions.
- Preserve interaction semantics, theme changes, contrast, reduced motion, and screenshot output.
- Pass unit, screenshot, static-analysis, and assembly gates, or record exact blocking evidence.
- Compare original, dependency-only, and styled profileable builds. Reject repeated startup or frame regressions above 5 percent.

## Ownership

- styles-integration owns this worktree, the style adapter, components, tests, and planning artifacts.
- The version catalog and build conventions have one writer, styles-integration.
- Any delegated writer must use a separate worktree and a recorded path allocation.
- Golden fixtures are read-only. Main integration and push are not authorized.
