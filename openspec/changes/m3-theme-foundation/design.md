## Context

Task ID: `UIX-1793256778917552`. Owner decisions of 2026-10-29: seed scheme with opt-in dynamic color, stable Expressive only with standard motion, `RipDpi*` facade. Work runs on `main`.

## Goals / Non-Goals

- Goal: a complete Material 3 theme that later phases can rely on.
- Non-goal: layout, navigation, or component structure changes. Those belong to later child changes.

## Decisions

- Generate the six schemes with Material Theme Builder from a low-chroma seed close to the current ink color. Commit the generated values as Kotlin constants, not a runtime dependency.
- Map RDS extended roles onto Material roles where they mean the same thing; keep only status roles and roles without a Material equivalent in `RipDpiExtendedColors`.
- Read contrast with `UiModeManager.getContrast()` and its listener on API 34+. Map the value to Standard, Medium, or High with fixed thresholds.
- Add one bool field to AppSettings with the next free field number, following the `protobuf-schema-evolution` skill. Use `dynamicLightColorScheme`/`dynamicDarkColorScheme` only when it is true and API is 31+.
- Build a custom `MotionScheme` whose specs come from `RipDpiMotion` buckets.
- Import Material Symbols Outlined (weight 400, grade 0, 24dp optical size) as vector drawables for the icons `RipDpiIcons` already exposes.

## Contracts and ownership

- AppSettings proto: one additive field. Backup export and restore include it.
- Locale resources: the new switch strings land in all ten locales in one commit.
- Guard tests: the rule against direct `MaterialTheme.colorScheme` reads is rewritten for the new source of truth, not removed.

## Risks / Trade-offs

- Nearly every screenshot fixture changes. Fixtures are recorded only after owner approval.
- Dynamic color can lower contrast between custom status colors and surfaces. Test status containers with dynamic color on.
