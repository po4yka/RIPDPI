---
title: Add Color Vision Accessibility Modes to Detection Check Screen
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Color Vision Accessibility Modes to Detection Check Screen #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Add a `StatusVisualResolver` with 4 color/shape palettes to `DetectionCheckScreen` so that users with color vision deficiencies can distinguish detection states without relying on green/red color alone.

## Context

RKNHardering's `StatusVisualResolver` maps 5 verdict states × 4 CVD palettes to color + shape + icon combinations. Each state is represented by a custom `StatusShapeDrawable`. RIPDPI uses a flat color card for the verdict hero — no shape differentiation. For users with deuteranopia or achromatopsia, DETECTED and NOT_DETECTED cards are indistinguishable.

**5 states:** CLEAN, REVIEW, DETECTED, ERROR, NEUTRAL
**4 palettes:**
- STANDARD — green / amber / red (default)
- RED_GREEN_SAFE — CVD-safe palette (deuteranopia / protanopia)
- TRITAN_SAFE — CVD-safe for tritanopia / blue-yellow
- ACHROMATOPSIA — monochrome only

**Shape variants (custom drawable):** CIRCLE, TRIANGLE (upward path), DIAMOND (path), SQUARE (rounded rect), LINE (horizontal bar)
Each palette maps each state to a unique color + shape combination so color-blind users still see distinct shapes.

**Reference:**
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/StatusVisualResolver.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/StatusShapeDrawable.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/ColorVisionMode.kt` — enum: OFF / RED_GREEN / BLUE_YELLOW / ACHROMATOPSIA

**RIPDPI placement:**
- New `core/detection` domain model: `DetectionColorVisionMode` enum
- New Compose `StatusVisualIndicator` composable using `Canvas` (Compose equivalent of `StatusShapeDrawable`)
- `DetectionCheckScreen` verdict hero uses `StatusVisualIndicator` driven by current `ColorVisionMode`
- `DetectionSettings` gains `colorVisionMode: DetectionColorVisionMode` (default OFF)
- Settings screen (see `add-detection-settings-screen`): Color Vision chips with live preview of all 5 states

**Easter egg:** 10 taps on the verdict hero → unlocks protanopia sub-variant of RED_GREEN palette; stored in `DetectionSettings`.

## Acceptance criteria

- [ ] `DetectionColorVisionMode` enum: OFF, RED_GREEN, BLUE_YELLOW, ACHROMATOPSIA
- [ ] `StatusVisualIndicator` composable: renders shape via `Canvas` for each state × mode combination
- [ ] Standard palette: CLEAN=circle+green, REVIEW=triangle+amber, DETECTED=diamond+red, ERROR=square+error-red, NEUTRAL=line+grey
- [ ] CVD palettes use distinct shapes so no two states share the same shape
- [ ] Live preview in settings shows all 5 states simultaneously
- [ ] Verdict hero card in `DetectionCheckScreen` uses `StatusVisualIndicator` instead of plain icon
- [ ] Each accordion category tile status dot also uses the shape system
- [ ] Easter egg: 10 taps unlocks protanopia variant; stored persistently
- [ ] Roborazzi goldens for each of the 4 palettes on the verdict hero

## TDD workflow

1. **Write tests first** — goldens-first: record must fail (no golden exists) before composable is built:
   - `app/src/screenshotTest/kotlin/com/poyka/ripdpi/ui/screens/detection/StatusVisualIndicatorTest.kt`:
     - One golden per palette × 5 states = 20 goldens (e.g. `standard_clean`, `standard_detected`, `red_green_review`, `achromatopsia_error`, …)
     - Each test renders `StatusVisualIndicator(state=X, palette=Y)` in isolation
   - `app/src/screenshotTest/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionColorVisionPreviewTest.kt`:
     - `all_5_states_visible_simultaneously()` — renders the live-preview row (all states side by side) for each palette; 4 goldens
   - Additionally, a pure logic test:
     - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/ui/StatusVisualResolverTest.kt`:
       - `no_two_states_share_same_shape_in_any_palette()` — for each palette, assert all 5 states produce distinct `ShapeVariant` values; fails until palette mappings defined
2. **Confirm red** — `./gradlew :app:recordRoborazziDebug` — no goldens exist yet; `./gradlew :core:detection:test` — shape-uniqueness test fails
3. **Implement** — `DetectionColorVisionMode`, `StatusVisualResolver`, `StatusVisualIndicator` composable (Canvas-based), wire into `DetectionCheckScreen` verdict hero and category tile dots
4. **Record goldens** — `./gradlew :app:recordRoborazziDebug`
5. **Confirm green** — `./gradlew :app:verifyRoborazziDebug :core:detection:test`
6. **Refactor** — extract shape path builders; ensure easter egg state does not affect non-protanopia palettes

## Definition of done

Goldens pass for all 4 palettes. Switching CVD mode in settings immediately updates all verdict indicators without re-running the scan.
