---
id: UIX-1793256778917552
title: Adopt the Material 3 theme foundation
kind: feature
status: review
area: ui
priority: high
owner: UI overhaul
parent: EPC-1793256695505524
blocked_by: []
spec_mode: required
openspec_change: m3-theme-foundation
created: 2026-10-29
updated: 2026-10-30
---

## Goal

Give the app a complete Material 3 theme: a seed-generated color scheme with three contrast levels that follow the system setting, an opt-in dynamic color setting, a motion scheme shared by Material and RDS components, the full type and shape scales, and Material Symbols icons. Screen layouts do not change in this phase.

## Acceptance criteria

- Light and dark schemes define every Material 3 color role for standard, medium, and high contrast. Status roles stay separate.
- On Android 14 and later the app follows the system contrast level without a restart. Material-hosted and RDS components both change.
- A Settings switch turns on wallpaper-based dynamic color on Android 12 and later. It is off by default and survives backup and restore. Its strings exist in all ten locales.
- `MaterialTheme` receives a motion scheme derived from `RipDpiMotion`. Reduced or disabled motion removes Material component animation.
- Typography defines all fifteen baseline and fifteen emphasized styles in Geist. Shapes define the increased corner tokens.
- `RipDpiIcons` uses Material Symbols Outlined; the `material-icons-*` artifacts are removed.
- `DESIGN.md`, `docs/design-system.md`, and the `material-3` and `android-compose-patterns` skills describe this contract. The Glance widget theme stays in sync.

## Ownership

Single writer on `main` (owner authorization of 2026-10-29). Serialized files: `gradle/libs.versions.toml`, `core/data/model/src/main/proto/app_settings.proto`, all locale resource sets, `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/`, and screenshot fixtures.
