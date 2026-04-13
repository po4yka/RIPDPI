# UI/UX Roadmap -- RIPDPI

Roadmap for design system compliance fixes, M3 Expressive adoption, and general UI/UX improvements. Derived from the full DESIGN.md + M3 compliance audit (April 2026).

**Current audit score: 7.8/10** across 98 UI files, 19 screens.

---

## Phase 0 -- Critical Audit Fixes

Fixes that directly violate DESIGN.md rules. No new features -- pure compliance.

**Target: 1 sprint. Resolves 28/33 audit violations (85%).**

### 0.1 Refactor DetectionCheckScreen to design system

**Files:** `ui/screens/detection/DetectionCheckScreen.kt`
**Violations fixed:** 21

- [ ] Replace raw `Scaffold` with `RipDpiContentScreenScaffold`
- [ ] Replace raw `TopAppBar` + `TopAppBarDefaults` with `RipDpiTopAppBar`
- [ ] Replace 2x raw `IconButton` with `RipDpiIconButton`
- [ ] Replace 15 raw Material icon imports with `RipDpiIcons` facade
  - Add missing icons to `RipDpiIcons`: `KeyboardArrowDown`, `KeyboardArrowUp`, `LocationOn`, `NetworkCheck`, `Public`, `Remove`, `Shield`, `Timer`, `Videocam`, `Visibility`, `Dns`
- [ ] Add `contentDescription` to back arrow icon (accessibility violation)
- [ ] Remove redundant `fontWeight = FontWeight.Bold` overrides on lines 509, 826
- [ ] Verify content respects `contentMaxWidth` after scaffold migration

### 0.2 Add missing color tokens to RipDpiExtendedColors

**Files:** `ui/theme/Color.kt`, + 5 component files
**Violations fixed:** 5

- [ ] Add `outline: Color` token (for focus indicators)
  - Light: `#757575`, Dark: `#616161` (or map to `foreground`)
- [ ] Add `outlineVariant: Color` token (for subtle borders)
  - Light: `#D0D0D0`, Dark: `#333333` (or map to `border`)
- [ ] Update `RipDpiButton.kt:103` -- replace `MaterialTheme.colorScheme.outline` with `colors.outline`
- [ ] Update `RipDpiIconButton.kt:106` -- same fix
- [ ] Update `RipDpiChip.kt:84` -- replace `MaterialTheme.colorScheme.outlineVariant` with `colors.outlineVariant`
- [ ] Update `RipDpiTextField.kt:247` -- same fix
- [ ] Update `RipDpiDropdown.kt:269` -- same fix

### 0.3 Fix RipDpiDialog destructive color

**Files:** `ui/components/feedback/RipDpiDialog.kt`
**Violations fixed:** 2

- [ ] Line 179: Replace `MaterialTheme.colorScheme.error.copy(alpha = 0.12f)` with `colors.destructiveContainer`
- [ ] Line 187: Replace `MaterialTheme.colorScheme.error` with `colors.destructive`

### 0.4 Fix ModeEditorRoute raw AlertDialog

**Files:** `ui/screens/config/ModeEditorRoute.kt`, `ModeEditorScreen.kt`
**Violations fixed:** 1-2

- [ ] Replace `AlertDialog` (line 190) with `RipDpiDialog`
- [ ] Remove unused `AlertDialog` import from `ModeEditorScreen.kt:23`

---

## Phase 1 -- Design Token Completion

Fill gaps in the token system so future development never needs raw Material fallbacks.

**Target: 1 sprint. Prevents regression.**

### 1.1 Complete shape token scale

**Files:** `ui/theme/Shape.kt`, `StageProgressIndicator.kt`, `AnalysisProgressIndicator.kt`

- [ ] Add `xs: Shape = RoundedCornerShape(4.dp)` to `RipDpiShapeTokens` (aligns with MD3 `extra-small`)
  - Alternatively: add 3dp if progress indicator design requires it -- document the exception
- [ ] Replace `RoundedCornerShape(3.dp)` in `StageProgressIndicator.kt:37` with `shapes.xs`
- [ ] Replace hardcoded shapes in `AnalysisProgressIndicator.kt:48,50,75,270` with tokens
- [ ] Add `xlIncreased`, `xxlIncreased`, `xxxl` to `RipDpiShapeTokens` implementation (already documented in DESIGN.md)

### 1.2 Fix hardcoded shapes in HomeScreen

**Files:** `ui/screens/home/HomeScreen.kt`

- [ ] Line 781: Replace `RoundedCornerShape(12.dp)` with `RipDpiThemeTokens.shapes.lg`
- [ ] Line 817: Replace `RoundedCornerShape(12.dp)` with `RipDpiThemeTokens.shapes.lg`
- [ ] Line 902: Replace `RoundedCornerShape(8.dp)` with `RipDpiThemeTokens.shapes.sm`

### 1.3 Tokenize elevation edge cases

**Files:** `ui/components/inputs/RipDpiSwitch.kt`, `ui/theme/Surface.kt`

- [ ] Add `SwitchThumb` to `RipDpiSurfaceRole` with `shadowElevation = 3.dp`
- [ ] Update `RipDpiSwitch.kt:292` to use the new surface role token

### 1.4 Fix RipDpiBottomSheet raw Material tokens

**Files:** `ui/components/feedback/RipDpiBottomSheet.kt`

- [ ] Line 77: Replace `MaterialTheme.colorScheme.surface` with `colors.card`
- [ ] Line 79: Add `scrim: Color` to `RipDpiExtendedColors` or use `Color.Black.copy(alpha = 0.32f)` via a named constant
- [ ] Line 297: Replace `MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f)` with the scrim token

### 1.5 Fix DiagnosticsCards raw icon imports

**Files:** `ui/screens/diagnostics/DiagnosticsCards.kt`

- [ ] Replace 2 raw Material icon imports with `RipDpiIcons` equivalents
- [ ] Add `KeyboardArrowDown` to `RipDpiIcons` if not already added in Phase 0

### 1.6 Add Expressive shape tokens to implementation

**Files:** `ui/theme/Shape.kt`

- [ ] Implement `xlIncreased = RoundedCornerShape(20.dp)` in `RipDpiShapeTokens`
- [ ] Implement `xxlIncreased = RoundedCornerShape(32.dp)`
- [ ] Implement `xxxl = RoundedCornerShape(48.dp)`
- [ ] Map to Material3 Shapes where applicable

---

## Phase 2 -- M3 Expressive Progressive Adoption

Adopt stable M3 Expressive features. Gate experimental APIs behind version checks.

**Target: 2 sprints. Depends on Compose BOM updates.**

### 2.1 Spring-based motion for interactive components

- [ ] Replace `tween()` with `spring()` in `RipDpiButton.kt` press animation
- [ ] Replace `tween()` with `spring()` in `RipDpiIconButton.kt` press animation
- [ ] Replace `tween()` with `spring()` in `RipDpiChip.kt` selection animation
- [ ] Keep `tween()` for route transitions (enter/exit) -- spring is for component-level only
- [ ] Ensure `reducedMotion` maps spring to critically-damped (no bounce)
- [ ] Gate behind `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` if needed
- [ ] Update `RipDpiMotion` with spring stiffness/damping constants

### 2.2 Shape morphing on press states

- [ ] Button: morph from `xl` (16dp) to `xlIncreased` (20dp) on press
- [ ] Chip: morph from `lg` (12dp) to `xl` (16dp) on selection
- [ ] Card: morph from `xl` (16dp) to `xlIncreased` (20dp) on selection (`PresetCard`)
- [ ] Implement via `animateShape()` or animated `RoundedCornerShape` with spring easing
- [ ] Disable morphing when `reducedMotion = true`

### 2.3 Emphasized typography tokens

- [ ] Add `bodyEmphasisBold` (700) to `RipDpiTextStyles` for double-emphasis contexts
- [ ] Add `screenTitleEmphasis` (700) if score display pattern repeats beyond DetectionCheckScreen
- [ ] Audit all selected/active states for correct emphasis level
- [ ] Promote any inline weight override that repeats across 3+ screens to a named token

### 2.4 Contrast level support

- [ ] Add `ContrastLevel` enum (Standard, Medium, High) to theme system
- [ ] Generate adjusted `RipDpiExtendedColors` variants per contrast level
- [ ] Expose contrast preference in Settings > Accessibility
- [ ] High contrast: thicker borders (2dp instead of 1dp), increased foreground/background separation
- [ ] Test all screens at each contrast level with screenshot tests

---

## Phase 3 -- Navigation & Layout Improvements

Improve app navigation flow and adaptive layout.

**Target: 2 sprints.**

### 3.1 Adaptive layout for tablets and foldables

- [ ] Audit all screens for Medium (600-839dp) and Expanded (>=840dp) behavior
- [ ] HomeScreen: split dashboard into two-column layout at Medium+ breakpoint
- [ ] ConfigScreen: side-by-side preset selection + editor at Expanded breakpoint
- [ ] DiagnosticsScreen: charts + cards side-by-side at Medium+ breakpoint
- [ ] SettingsScreens: list-detail split at Expanded breakpoint
- [ ] Test on foldable emulator -- ensure no content crosses the hinge
- [ ] Add screenshot test variants for Medium and Expanded width classes

### 3.2 Navigation transitions polish

- [ ] Audit all route transitions for consistent enter/exit easing
- [ ] Implement shared element transitions for Home -> Config mode editor flow
- [ ] Add predictive back gesture animation support (Android 14+)
- [ ] Ensure all transitions use `routeDurationMillis` (260ms) consistently

### 3.3 Deep linking and shortcuts

- [ ] Add deep link support for `ripdpi://connect`, `ripdpi://settings`, `ripdpi://diagnostics`
- [ ] Add Android App Shortcuts for "Quick Connect" and "Run Diagnostics"
- [ ] Verify deep links work with notification tap actions

---

## Phase 4 -- UX Enhancements

New user-facing improvements beyond design system compliance.

**Target: 3 sprints.**

### 4.1 Theme preference UI

The theme system supports dark/light/system but has no user-facing toggle.

- [ ] Add "Appearance" section to Settings > Preferences
- [ ] Implement theme selector: System (default), Light, Dark
- [ ] Add smooth theme transition animation (cross-fade or circular reveal)
- [ ] Persist preference via existing `themePreference` DataStore field
- [ ] Preview both themes in selector using mini-preview cards

### 4.2 Connection status improvements

- [ ] Add connection duration timer to HomeScreen when VPN is active
- [ ] Add data transferred counter (up/down) to HomeScreen status area
- [ ] Add connection quality indicator (latency-based) using `StatusIndicator` tones
- [ ] Animate status transitions (Idle -> Connecting -> Connected) with sequential spring animations
- [ ] Add haptic feedback on connect/disconnect state changes

### 4.3 Onboarding flow refinements

- [ ] Add skip option for advanced users (jump to Home after VPN permission)
- [ ] Add "What is DPI?" educational card in onboarding
- [ ] Add connection test retry with exponential backoff indicator
- [ ] Improve error states with actionable recovery suggestions
- [ ] Add animated illustrations for each onboarding step

### 4.4 Diagnostics UX

- [ ] Add export/share for diagnostics reports (PDF or text summary)
- [ ] Add historical comparison view (current scan vs previous)
- [ ] Add detection score trend chart (sparkline in History screen)
- [ ] Improve scan progress with estimated time remaining
- [ ] Add result explanation tooltips for non-technical users

### 4.5 Settings discoverability

- [ ] Add search functionality to Settings screens
- [ ] Add "Recommended" badges on settings that improve bypass for user's region
- [ ] Group advanced settings behind expandable sections with clear labels
- [ ] Add reset-to-defaults option per settings group
- [ ] Add setting change confirmation for critical options (desync mode, DNS provider)

### 4.6 Notification and Quick Settings polish

- [ ] Improve Quick Settings tile with connection status icon variants
- [ ] Add persistent notification with data counter and quick disconnect action
- [ ] Add notification channel for diagnostics scan completion
- [ ] Style notifications with design system colors (monochrome + semantic accents)

---

## Phase 5 -- Accessibility & Internationalization

**Target: 2 sprints.**

### 5.1 Accessibility hardening

- [ ] Run TalkBack end-to-end test on all 19 screens
- [ ] Add live region announcements for connection state changes
- [ ] Add custom accessibility actions for complex components (long-press alternatives)
- [ ] Verify all color contrast ratios meet WCAG AA (4.5:1 for text, 3:1 for UI)
- [ ] Add keyboard/D-pad navigation support for TV/desktop form factors
- [ ] Implement contrast level UI from Phase 2.4

### 5.2 RTL layout support

- [ ] Audit all screens for RTL layout correctness
- [ ] Replace hardcoded `start`/`end` padding with RTL-aware equivalents where missing
- [ ] Test with Arabic/Hebrew locale on all screens
- [ ] Add RTL screenshot test variants

### 5.3 Localization infrastructure

- [ ] Add language selector to Settings > Preferences (after theme toggle)
- [ ] Audit all hardcoded English strings (especially in component code)
- [ ] Add pluralization support for counter displays
- [ ] Add content description translations for all accessibility labels

---

## Phase 6 -- Screenshot Test Coverage

Ensure visual regression safety for all changes above.

**Target: ongoing, parallel to other phases.**

### 6.1 Expand screenshot catalogs

- [ ] Add DetectionCheckScreen to `RipDpiScreenPreviewCatalog` (currently missing)
- [ ] Add ModeEditorScreen previews
- [ ] Add DiagnosticsScreen scan-in-progress state
- [ ] Add all error/empty states per screen

### 6.2 Multi-configuration screenshots

- [ ] Add Medium (600dp) width class variants for all screens
- [ ] Add Expanded (840dp) width class variants for key screens
- [ ] Add high-contrast theme variants (when Phase 2.4 ships)
- [ ] Add RTL variants (when Phase 5.2 ships)
- [ ] Add large-font (1.5x) variants for accessibility verification

### 6.3 Golden test maintenance

- [ ] Integrate screenshot verification into PR checks
- [ ] Add auto-bless workflow for intentional visual changes
- [ ] Document screenshot update process in CONTRIBUTING.md

---

## Success Metrics

| Metric | Current | Phase 0 | Phase 1 | Phase 2+ |
|--------|---------|---------|---------|----------|
| Audit score | 7.8/10 | 9.5/10 | 10/10 | 10/10 |
| Screens with violations | 2/19 | 0/19 | 0/19 | 0/19 |
| Design token coverage | 93% | 98% | 100% | 100% |
| Expressive features adopted | 2/6 | 2/6 | 4/6 | 6/6 |
| Screenshot test coverage | ~60% | ~60% | ~75% | ~95% |
| Accessibility score | 8/10 | 8.5/10 | 9/10 | 10/10 |

---

## Dependencies

| Phase | Blocked by |
|-------|-----------|
| Phase 0 | Nothing -- start immediately |
| Phase 1 | Nothing -- can run parallel with Phase 0 |
| Phase 2 | Compose BOM update with stable Expressive APIs |
| Phase 3 | Phase 0 + 1 (clean baseline before layout changes) |
| Phase 4 | Phase 0 + 1 (clean baseline before new features) |
| Phase 5 | Phase 2.4 (contrast levels), Phase 4.1 (theme toggle) |
| Phase 6 | Parallel to all phases |

---

## References

- [DESIGN.md](DESIGN.md) -- Design system specification
- [Material 3 Expressive skill](.claude/skills/material-3/SKILL.md) -- M3 guidelines
- [Audit findings (April 2026)](~/GitRep/my-ai-agents-second-brain/00_Inbox/2026-04-13%20-%20RIPDPI%20M3%20Design%20System%20Audit%20Findings.md) -- Full audit data
