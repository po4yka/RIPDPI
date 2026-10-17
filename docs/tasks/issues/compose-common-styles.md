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
- styles-adapter owns new generic style property/modifier files, the component style catalog and custom state keys, their tests, the Button/IconButton candidate, and input shells (Chip, Switch, SegmentedButton, TextField, Dropdown) in a separate worktree. It must not change other existing components, dependencies, task state, or golden fixtures. All component source preparation is provisional. Integration and dependent validation wait for the dependency gate.
- styles-bench owns only the benchmark module build script, a new benchmark-source test class, and new benchmark-only app launcher sources in `RIPDPI-compose-styles-bench`. It must not edit dependencies, CI policy, production sources, fixtures, or task state. This candidate is independent of the component migration.
- styles-integration also prepares shared surfaces in `RIPDPI-compose-styles-surfaces`. This candidate owns existing card and route visual-shell code, `RipDpiAccordion.kt`, `RipDpiConnectionActuator.kt`, the new surface-catalog extension and surface test files, and design documentation. It does not edit other controls, core style catalog files, dependencies, task state, or fixtures. Integration waits for the dependency gate.
- styles-integration owns the new `ComposeStylesInstrumentedTest.kt` device characterization in the surface candidate.
- styles-adapter also owns feedback and indicator visual shells and the new feedback-catalog extension file in its isolated candidate. Material hosts and Canvas renderers retain their native APIs. No fixtures may change.
- styles-adapter owns the new three-state performance comparison script and its parser/threshold tests in its candidate worktree. It must not change existing benchmark baselines or CI thresholds.
- styles-screens prepares eligible screen recipes plus scaffold/chrome backgrounds in `RIPDPI-compose-styles-screens`. It owns screen sources, scaffold/chrome sources, and a new screen-catalog extension file. It does not edit controls, cards, routes, feedback, indicators, core catalog files, dependencies, task state, or fixtures. Integration waits for the dependency gate.
- Golden fixtures are read-only. Main integration and push are not authorized.
