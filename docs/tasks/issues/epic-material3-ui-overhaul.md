---
id: EPC-1793256695505524
title: Epic - Material 3 UI and UX overhaul
kind: epic
status: review
area: epic
priority: high
owner: UI overhaul
parent: null
blocked_by: []
spec_mode: required
openspec_change: material3-ui-overhaul
created: 2026-10-29
updated: 2026-10-31
---

## Goal

Move the Android app UI to current Material 3 guidance: a complete M3 color, type, shape, and motion theme; adaptive navigation for every window size class; shared `RipDpi*` components built on Material 3 components; and canonical adaptive layouts on the main screens. The app keeps its calm operator-tool identity and its semantic status colors.

## Why now

The app already runs on a beta Compose BOM with the Material 3 1.5 line, but it uses only part of the system. Contrast levels exist only in previews, Material-hosted components use a different motion language from RDS components, navigation chrome is custom, the icon library is deprecated, and large windows get one stretched layout. Three earlier UI changes must close first so the overhaul starts from a clean base.

## Key decisions

The owner chose these on 2026-10-29:

- Color: a full M3 tonal scheme generated from a low-chroma brand seed, with standard, medium, and high contrast variants. Wallpaper-based dynamic color is an opt-in setting on Android 12 and later. Status colors (success, warning, info, restricted) stay separate semantic roles.
- Expressive: only stable Material 3 1.5 Expressive components, with `MotionScheme.standard()`-style motion. No shape morphing, no expressive spring motion, no experimental `LoadingIndicator` or `MaterialShapes`.
- Components: screens keep calling the `RipDpi*` facade. Its internals move to Material 3 components; RDS layers that Material 3 covers are deleted.
- Navigation library: Navigation Compose stays. Navigation 3 is out of scope.
- Integration: the owner authorized work, commits, and push directly on `main`.

## Scope

- In scope: theme foundation, adaptive app shell, component facade, canonical screen layouts, Material Symbols icons, design contract and harness updates, screenshot fixtures, and device acceptance.
- Out of scope: Navigation 3, network or service behavior, Rust and JNI code, and the Glance widget layout (only its theme stays in sync).

## Child tasks

Each phase is blocked by the previous one:

1. [[m3-theme-foundation]]: color scheme, contrast, dynamic color setting, motion scheme, type and shape scales, Material Symbols, design contract and harness updates.
2. [[m3-adaptive-app-shell]]: `NavigationSuiteScaffold`, window size classes up to extra-large, M3 top app bars, predictive back.
3. [[m3-component-facade]]: `RipDpi*` internals on Material 3 components and deletion of covered RDS layers.
4. [[m3-screen-layouts]]: list-detail and supporting-pane layouts, action placement, and search on the main screen families.

## Ship definition

- [ ] All four child tasks are done with closed OpenSpec changes.
- [ ] Light, dark, medium contrast, high contrast, and dynamic color render correctly on a phone and on a tablet or foldable, at 200% font and in RTL.
- [ ] No `material-icons-core` or `material-icons-extended` dependency remains.
- [ ] `DESIGN.md`, `docs/design-system.md`, and the project skills describe the Material 3 contract.

## Risks / open questions

- Most of the 430 Roborazzi fixtures change. Each phase needs a separate owner approval before golden-blesser records them.
- The RDS HTML deck is spec-owner-managed. The owner regenerates it; agents only update `COVERAGE.md`.
- Material 3 1.5 is in beta. Pin the versions the BOM resolves and record them.

## References

- `DESIGN.md`, `docs/design-system.md`, `docs/design/rds/`.
- Material 3 for Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Window size classes: https://developer.android.com/develop/ui/compose/layouts/adaptive/window-size-classes
