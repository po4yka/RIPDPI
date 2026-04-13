# DESIGN.md -- RIPDPI

A plain-text design system document for AI coding agents.
Drop this file into your agent's context to generate UI that matches RIPDPI's visual language.

---

## 1. Visual Theme & Atmosphere

- **Mood**: Technical, precise, utilitarian. A networking tool that feels like a well-built instrument panel -- not playful, not corporate.
- **Density**: Medium-high. Settings-heavy screens pack information tightly; dashboards breathe more.
- **Philosophy**: Monochrome-first with semantic color only for status and feedback. No decorative gradients, no brand colors beyond the pixel-art brand mark. Every visual element earns its place through function.
- **Platform**: Android (Jetpack Compose, Material 3 / Material You). Minimum SDK 27 (Android 8.1).

---

## 2. Color Palette & Roles

All colors are defined in `RipDpiExtendedColors`. The system is fully dual-theme (light + dark). Never hardcode hex values in screens -- always use `RipDpiThemeTokens.colors.*`.

### Light Theme

| Role | Hex | Usage |
|------|-----|-------|
| `background` | `#FAFAFA` | App canvas |
| `foreground` | `#1A1A1A` | Primary text, primary button fill |
| `card` | `#FFFFFF` | Card surfaces |
| `cardForeground` | `#1A1A1A` | Text on cards |
| `muted` | `#F5F5F5` | Subtle backgrounds, secondary containers |
| `mutedForeground` | `#575757` | Secondary text, placeholders, disabled content |
| `accent` | `#E8E8E8` | Selected card backgrounds, status card fills |
| `accentForeground` | `#1A1A1A` | Text on accent surfaces |
| `border` | `#E0E0E0` | Default borders |
| `cardBorder` | `#E8E8E8` | Card outline borders |
| `inputBackground` | `#F5F5F5` | Text field and tonal card fills |
| `divider` | `#F0F0F0` | Section dividers |
| `hairline` | `#666666` | Fine 0.5dp detail lines |
| `success` | `#047857` | Connected, healthy, passing |
| `warning` | `#B45309` | Degraded, attention needed |
| `warningForeground` | `#FFFFFF` | Text on warning fills |
| `warningContainer` | `#FFF7ED` | Warning banner backgrounds |
| `warningContainerForeground` | `#1A1A1A` | Text on warning banners |
| `destructive` | `#B91C1C` | Error, disconnect, delete |
| `destructiveForeground` | `#FFFFFF` | Text on destructive fills |
| `destructiveContainer` | `#FEF2F2` | Error banner backgrounds |
| `destructiveContainerForeground` | `#7F1D1D` | Text on error banners |
| `info` | `#1D4ED8` | Informational highlights |
| `infoForeground` | `#FFFFFF` | Text on info fills |
| `infoContainer` | `#EFF6FF` | Info banner backgrounds |
| `infoContainerForeground` | `#1E3A8A` | Text on info banners |
| `restricted` | `#6B7280` | Restricted / unavailable state |
| `restrictedForeground` | `#FFFFFF` | Text on restricted fills |
| `restrictedContainer` | `#F3F4F6` | Restricted banner backgrounds |
| `restrictedContainerForeground` | `#374151` | Text on restricted banners |

### Dark Theme

| Role | Hex | Usage |
|------|-----|-------|
| `background` | `#121212` | App canvas |
| `foreground` | `#E8E8E8` | Primary text, primary button fill |
| `card` | `#1A1A1A` | Card surfaces |
| `cardForeground` | `#E8E8E8` | Text on cards |
| `muted` | `#1E1E1E` | Subtle backgrounds |
| `mutedForeground` | `#A3A3A3` | Secondary text, placeholders |
| `accent` | `#2A2A2A` | Selected card backgrounds |
| `accentForeground` | `#E8E8E8` | Text on accent surfaces |
| `border` | `#2A2A2A` | Default borders |
| `cardBorder` | `#2A2A2A` | Card outline borders |
| `inputBackground` | `#1E1E1E` | Text field fills |
| `divider` | `#222222` | Section dividers |
| `hairline` | `#666666` | Fine detail lines |
| `success` | `#34D399` | Connected, healthy |
| `warning` | `#FBBF24` | Degraded state |
| `warningForeground` | `#1C1917` | Text on warning |
| `warningContainer` | `#451A03` | Warning backgrounds |
| `warningContainerForeground` | `#FDE68A` | Text on warning backgrounds |
| `destructive` | `#F87171` | Error, disconnect |
| `destructiveForeground` | `#210A0A` | Text on destructive |
| `destructiveContainer` | `#450A0A` | Error backgrounds |
| `destructiveContainerForeground` | `#FECACA` | Text on error backgrounds |
| `info` | `#60A5FA` | Informational |
| `infoForeground` | `#0A2342` | Text on info |
| `infoContainer` | `#0C4A6E` | Info backgrounds |
| `infoContainerForeground` | `#DBEAFE` | Text on info backgrounds |
| `restricted` | `#9CA3AF` | Restricted state |
| `restrictedForeground` | `#111827` | Text on restricted |
| `restrictedContainer` | `#1F2937` | Restricted backgrounds |
| `restrictedContainerForeground` | `#E5E7EB` | Text on restricted backgrounds |

### Semantic color rules

- **Success/Warning/Destructive/Info/Restricted** each have four roles: `base`, `foreground`, `container`, `containerForeground`. Use `container` variants for banners and badges; `base` for inline indicators and filled buttons.
- Never use raw Material `colorScheme.error` -- use `colors.destructive` instead.
- The palette is intentionally achromatic for core UI. Color only enters through status semantics.

---

## 3. Typography Rules

Three font families, all Geist:

| Family | Font | Weights | Usage |
|--------|------|---------|-------|
| `GeistSansFamily` | Geist Sans | Normal (400), Medium (500), Bold (700) | All UI text |
| `GeistMonoFamily` | Geist Mono | Normal (400), Medium (500), Bold (700) | Values, configs, logs |
| `GeistPixelCircleFamily` | Geist Pixel Circle | Normal (400) | Brand mark only |

### Type Scale (`RipDpiTextStyles`)

| Token | Family | Size | Line Height | Weight | Letter Spacing | Usage |
|-------|--------|------|-------------|--------|---------------|-------|
| `screenTitle` | Sans | 22sp | 28sp | Medium | 0 | Screen titles |
| `appBarTitle` | Sans | 20sp | 28sp | Medium | 0 | Top app bar titles |
| `sheetTitle` | Sans | 18sp | 24sp | Medium | 0 | Bottom sheet titles |
| `sectionTitle` | Sans | 13sp | 18sp | Medium | 0.72sp | Section headers, uppercase feel |
| `introAction` | Sans | 14sp | 21sp | Normal | 0 | Intro screen action text |
| `introTitle` | Sans | 22sp | 30sp | Medium | 0 | Intro screen titles |
| `introBody` | Sans | 15sp | 22sp | Normal | 0 | Intro screen body text |
| `body` | Sans | 14sp | 20sp | Normal | 0 | Primary body text |
| `bodyEmphasis` | Sans | 14sp | 20sp | Medium | 0 | Emphasized body text |
| `secondaryBody` | Sans | 14sp | 20sp | Normal | 0 | Secondary/helper text |
| `caption` | Sans | 12sp | 16sp | Normal | 0 | Captions, helper text |
| `smallLabel` | Sans | 12sp | 16sp | Medium | 0 | Small labels |
| `button` | Sans | 15sp | 20sp | Medium | 0 | Button labels |
| `navLabel` | Sans | 12sp | 16sp | Medium | 0 | Bottom nav labels |
| `monoValue` | Mono | 14sp | 20sp | Normal | 0 | Numeric values, ports, IPs |
| `monoConfig` | Mono | 14sp | 20sp | Normal | 0 | Configuration strings |
| `monoInline` | Mono | 13sp | 20sp | Normal | 0 | Inline code references |
| `monoLog` | Mono | 12sp | 20sp | Normal | 0 | Log entries |
| `monoSmall` | Mono | 12sp | 16sp | Normal | 0 | Dense telemetry, counters |
| `brandMark` | Pixel | 32sp | 48sp | Normal | 0.8sp | Home screen brand header |
| `brandStatus` | Mono | 13sp | 18sp | Medium | 0 | Status badges, telemetry labels |

### Emphasized Type Styles (M3 Expressive)

M3 Expressive adds 15 emphasized variants (one per baseline style) with higher weight for selected/active states. RIPDPI maps this concept through its existing weight system:

| Context | Baseline Style | Emphasized Equivalent | How to apply |
|---------|---------------|----------------------|--------------|
| Selected tab / nav item | `navLabel` | `navLabel` + Medium (500) | Already Medium weight -- use `foreground` color for emphasis |
| Active card title | `body` (400) | `bodyEmphasis` (500) | Switch to `bodyEmphasis` on selection |
| Important setting value | `monoValue` (400) | `monoValue` + Medium (500) | Apply `fontWeight = FontWeight.Medium` inline |
| Unread / attention items | `body` (400) | `bodyEmphasis` (500) | Use `bodyEmphasis` for unread content |
| Section header emphasis | `sectionTitle` (500) | `sectionTitle` + Bold (700) | Apply `fontWeight = FontWeight.Bold` for active sections |

**Rule**: Prefer weight promotion (400 -> 500 -> 700) over size increase for emphasis. This follows M3 Expressive's approach and keeps the layout stable when items transition between states.

**Adoption status**: RIPDPI does not define separate `emphasized*` text style tokens. Instead, use `bodyEmphasis` for body-level emphasis and apply inline weight overrides for other scales. If a pattern recurs across 3+ screens, promote it to a named style in `RipDpiTextStyles`.

### Typography constraints

- `brandMark` is reserved for the home brand header only. Never use it elsewhere.
- `brandStatus` is reserved for compact status labels and telemetry badges.
- `sectionTitle` is reserved for section headers.
- Do not override typography inline for branding. If a screen needs special treatment, create a scoped component.
- Prefer weight promotion over size increase for emphasis (M3 Expressive principle).
- All text styles use `PlatformTextStyle(includeFontPadding = false)`.

---

## 4. Component Catalog

Access all tokens via `RipDpiThemeTokens` (`.colors`, `.type`, `.spacing`, `.layout`, `.components`, `.shapes`, `.motion`).

### Buttons

**`RipDpiButton`** -- Primary action component.

| Variant | Container | Content | Border | Use case |
|---------|-----------|---------|--------|----------|
| `Primary` | `foreground` | `background` | none | Main CTA (Connect, Save) |
| `Secondary` | `secondary` | `onSecondary` | none | Alternate actions |
| `Outline` | transparent | `foreground` | `border` 1dp | Cancel, dismiss |
| `Ghost` | transparent | `foreground` | none | Tertiary actions |
| `Destructive` | `destructive` | `destructiveForeground` | none | Delete, reset |

States: default, pressed (0.98x scale + color lerp), disabled (filled variants: `border` bg + `mutedForeground` text; Outline/Ghost: transparent bg + `mutedForeground` text), loading (spinner replaces leading icon, 0.92 alpha). Shape: `shapes.xl` (16dp radius). Horizontal padding: 20dp. Supports `leadingIcon`, `trailingIcon`, and `density: Compact` (-4dp padding).

#### Button Sizes (M3 Expressive)

M3 Expressive defines 5 button sizes. RIPDPI maps them as:

| M3 Size | Height | RIPDPI Mapping | Use case |
|---------|--------|----------------|----------|
| XS | 32dp | `Compact` density | Dense toolbars, inline actions |
| S | 40dp | -- | M3 default (not used; RIPDPI default is 48dp) |
| M | 48dp | Default (`buttonMinHeight`) | Standard CTA, primary actions |
| L | 56dp | -- | Available for hero CTAs |
| XL | 64dp | -- | Available for onboarding/intro flows |

RIPDPI's default button height remains 48dp (M3 Medium) to match the 48dp minimum touch target. Use `Compact` density for XS contexts. L and XL sizes are available for intro/onboarding screens where larger touch targets improve usability.

#### Toggle Buttons (M3 Expressive)

M3 Expressive adds toggle (selection) support to standard buttons. In RIPDPI, use `RipDpiChip` for filter/selection toggle patterns. For icon-level toggle, use `RipDpiIconButton` with its existing `toggle`/`selected` state.

**`RipDpiIconButton`** -- Icon-only action. Size: 48dp. Styles: `Ghost` (default), `Tonal`. Always requires `contentDescription`.

### Cards

**`RipDpiCard`** -- Content container.

| Variant | Surface | Border | Elevation | Use case |
|---------|---------|--------|-----------|----------|
| `Outlined` | `surface` | `cardBorder` 1dp | 0dp | Default list blocks, grouped controls |
| `Tonal` | `inputBackground` | `border` 1dp | 0dp | Inline groups, selected settings |
| `Elevated` | `surface` | `cardBorder` 0.72 alpha | 18dp | Dialogs, sheets, priority content |
| `Status` | `accent` | `cardBorder` 1dp | 8dp | Status dashboard cards |

Shape: `shapes.xl` (16dp radius). Padding: `layout.cardPadding` (16-20dp adaptive). Content spacing: `spacing.sm` (8dp).

**`PresetCard`** -- Selectable card with selection state. When selected: `accent` bg, `foreground` border, 4dp elevation. Must expose selection state semantically.

**`SettingsRow`** -- List row for settings screens.

| Variant | Background | Border | Use case |
|---------|------------|--------|----------|
| `Default` | transparent | none | Standard settings row |
| `Tonal` | `inputBackground` | `border` | Grouped/highlighted row |
| `Selected` | `accent` | `foreground` | Active selection |

Min height: 52dp (no subtitle), 68dp (with subtitle). Supports: `title`, `subtitle`, `value`, `checked` (toggle), `leadingIcon`, `showChevron`, `showDivider`.

### Inputs

**`RipDpiTextField`** -- Text input field.

- Background: `inputBackground`. Shape: `shapes.xl` (16dp).
- Border: 1dp `outlineVariant` default, 2dp `foreground` focused, 2dp `destructive` error, 1dp `border` disabled.
- Text style: `monoValue` (default), `monoConfig` (for `RipDpiConfigTextField`).
- Supports: `label`, `placeholder`, `helperText`, `errorText`, `trailingContent`.
- Multiline variant (`RipDpiConfigTextField`): min height 96dp.

**`RipDpiSwitch`** -- Toggle control.

- Dimensions: 52dp wide, 48dp hit area, 32dp track, 24dp thumb with 4dp padding.
- Track: monochrome blend (foreground/background). Checked = `foreground` track, `background` thumb.
- Supports labeled mode with `label`, `helperText`, `errorText`.
- Disabled: 0.38 alpha.
- Accessibility: exposes `stateDescription` ("On"/"Off"), `contentDescription` from label, error semantics.

**`RipDpiChip`** -- Filter/selection chip.

- Shape: `shapes.lg` (12dp). Border: 1dp `outlineVariant` (unselected), `foreground` (selected).
- Selected: `foreground` fill, `background` text, check icon. Unselected: transparent, `foreground` text.
- Press scale: 0.98x. Selection scale: 1.02x.
- Horizontal padding: 17dp. Vertical padding: 6dp.

**`RipDpiDropdown`** -- Dropdown selector (wraps text field with menu).

### Containers

**`RipDpiDialog`** -- Modal dialog. Max width: `layout.dialogMaxWidth`. Icon size: 40dp.

**`RipDpiBottomSheet`** -- Bottom sheet. Handle: 36dp x 4dp, `mutedForeground` color.

**`RipDpiSnackbar`** -- Toast notification. Max width: `layout.snackbarMaxWidth`. Uses `inverseSurface`/`inverseOnSurface`. Elevation: 18dp.

**`WarningBanner`** -- Inline warning banner.

### Navigation

**`RipDpiTopAppBar`** -- Custom top bar (not Material TopAppBar).

- Title: `appBarTitle` style, `foreground` color, single line with ellipsis.
- Navigation icon: optional `RipDpiIconButton` (Ghost style).
- Actions: right-aligned row with `spacing.sm` (8dp) gaps.
- Min height: `layout.appBarMinHeight` (56-64dp adaptive).
- Constrained to `contentMaxWidth + 2 * horizontalPadding`.

**`BottomNavBar`** -- Bottom navigation. Indicator: 64dp x 28dp, offset 10dp from top.

**`SettingsCategoryHeader`** -- Category label for settings groups.

### Indicators

**`StatusIndicator`** -- Connection/system status dot with label.

| Tone | Color | Shape | Pulse |
|------|-------|-------|-------|
| `Active` | `foreground` | Circle 8dp | Yes (1x to 1.8x, fading) |
| `Idle` | `mutedForeground` | Diamond 9dp | No |
| `Warning` | `warning` | Triangle 10dp | Yes |
| `Error` | `destructive` | Square 8dp | No |

Label: `brandStatus` style. Pulse animation: infinite, `emphasizedDuration * 2` cycle, linear easing.

**`RipDpiPageIndicators`** -- Pager dot indicators.

**`LogRow`** -- Log entry row with monospace text.

**`StageProgressIndicator`** -- Multi-stage progress display.

**`AnalysisProgressIndicator`** -- Diagnostics progress display.

---

## 5. Layout Principles

### Spacing Scale (`RipDpiSpacing`)

| Token | Value | Usage |
|-------|-------|-------|
| `xs` | 4dp | Tight internal gaps, label-to-field |
| `sm` | 8dp | Card content spacing, button icon gaps |
| `md` | 12dp | Row item spacing, app bar element gaps |
| `lg` | 16dp | Card padding (compact), group gaps (compact) |
| `xl` | 20dp | Section gaps (compact), horizontal screen padding (compact) |
| `xxl` | 24dp | Bottom content padding |
| `xxxl` | 32dp | Large section breaks |
| `section` | 40dp | Major section dividers |
| `screen` | 48dp | Screen-level spacing |

### Adaptive Layout (`RipDpiLayout`)

Three width classes drive all responsive behavior:

| Class | Breakpoint | Grouping | Horiz. Padding | Content Max | Form Max | Dialog Max | Snackbar Max | Section Gap | App Bar Height |
|-------|-----------|----------|---------------|-------------|----------|-----------|-------------|-------------|---------------|
| `Compact` | < 600dp | `SingleColumn` | 20dp | 560dp | 520dp | 560dp | 560dp | 20dp | 56dp |
| `Medium` | 600-839dp | `CenteredColumn` | 28dp | 720dp | 600dp | 640dp | 640dp | 24dp | 60dp |
| `Expanded` | >= 840dp | `SplitColumns` | 32dp | 960dp | 680dp | 720dp | 720dp | 28dp | 64dp |

### Scaffold Rules

| Scaffold | Width Constraint | Scroll | Use case |
|----------|-----------------|--------|----------|
| `RipDpiContentScreenScaffold` | `Content` or `Form` | Vertical scroll | General content screens |
| `RipDpiSettingsScaffold` | `Form` | LazyColumn | Settings and list screens |
| `RipDpiDashboardScaffold` | `Content` (Dashboard) | Vertical scroll | Home and dashboard screens |
| `RipDpiIntroScaffold` | `Form` (capped at Content) | None (center + bottom) | Onboarding and auth flows |

All scaffolds use `RipDpiScreenScaffold` as base, which sets `containerColor = colors.background` and applies automation tree root.

---

## 6. Shapes & Corner Radii

Defined in `RipDpiShapeTokens`:

| Token | Radius | Compose Shape | MD3 Equivalent | Usage |
|-------|--------|--------------|----------------|-------|
| `sm` | 8dp | `RoundedCornerShape(8.dp)` | `small` (8dp) | Compact elements |
| `md` | 10dp | `RoundedCornerShape(10.dp)` | -- | Medium elements |
| `lg` | 12dp | `RoundedCornerShape(12.dp)` | `medium` (12dp) | Chips, settings rows |
| `xl` | 16dp | `RoundedCornerShape(16.dp)` | `large` (16dp) | Buttons, cards, text fields |
| `xlIncreased` | 20dp | `RoundedCornerShape(20.dp)` | `large-increased` (20dp) | Expressive interactive surfaces |
| `xxl` | 28dp | `RoundedCornerShape(28.dp)` | `extra-large` (28dp) | Pills, FABs |
| `xxlIncreased` | 32dp | `RoundedCornerShape(32.dp)` | `extra-large-increased` (32dp) | Expressive containers, morphed states |
| `xxxl` | 48dp | `RoundedCornerShape(48.dp)` | `extra-extra-large` (48dp) | Expressive hero elements |
| `full` | 50% | `CircleShape` | `full` (9999px) | Avatars, status dots, switch thumbs |

The `xlIncreased`, `xxlIncreased`, and `xxxl` tokens come from the M3 Expressive shape scale. Use them for new interactive surfaces, morphed pressed/selected states, and hero elements. Existing components retain their current shape assignments.

### Stroke

| Token | Width | Usage |
|-------|-------|-------|
| `Thin` | 1dp | Standard borders |
| `Hairline` | 0.5dp | Fine detail lines |

---

## 7. Depth & Elevation

Surface roles define the elevation hierarchy:

| Role | Elevation | Usage |
|------|-----------|-------|
| `Card` | 0dp | Default cards, list items |
| `TonalCard` | 0dp | Tonal/grouped cards |
| `StatusCard` | 8dp | Status dashboard cards |
| `SelectedCard` | 4dp | Selected/active cards |
| `ElevatedCard` | 18dp | Priority content, dialogs |
| `Snackbar` | 18dp | Toast notifications |
| `Sheet` | 24dp | Bottom sheets |
| `Banner` | 0dp | Inline banners |

Shadow clipping: `shadow(elevation, shape, clip = false)` -- shadows render outside bounds.

---

## 8. Motion & Animation

Defined in `RipDpiMotion`:

| Token | Duration | Usage |
|-------|----------|-------|
| `quickDurationMillis` | 120ms | Press/release, border changes |
| `stateDurationMillis` | 220ms | Color transitions, state changes |
| `emphasizedDurationMillis` | 320ms | Emphasized entries/exits |
| `routeDurationMillis` | 260ms | Screen transitions |

| Scale Token | Value | Usage |
|-------------|-------|-------|
| `pressScale` | 0.98 | Pressed state shrink |
| `selectionScale` | 1.02 | Selection pop |
| `emphasisScale` | 1.04 | Emphasis bounce |

### Easing Curves (M3)

| Curve | Values | Usage |
|-------|--------|-------|
| `EmphasizedDecelerate` | (0.05, 0.7, 0.1, 1.0) | Entering elements |
| `EmphasizedAccelerate` | (0.3, 0.0, 0.8, 0.15) | Exiting elements |
| `StandardEasing` | (0.2, 0.0, 0.0, 1.0) | On-screen property changes |

### Spring-Based Motion (M3 Expressive)

M3 Expressive replaces fixed easing curves with spring physics for component animations. Springs have no fixed duration -- they respond dynamically to input velocity and settling.

Two spring schemes are defined:

| Scheme | Character | Use case |
|--------|-----------|----------|
| **Standard** | Critically damped, no overshoot | Utility transitions, toggles, state changes |
| **Expressive** | Under-damped, slight bounce | Selection pops, FAB morphs, hero emphasis |

**Adoption status**: RIPDPI currently uses `tween()` + easing curves (the legacy M3 system). Spring-based motion is available in Compose via `spring()` and should be preferred for new interactive component animations once the Expressive API stabilizes. The easing/duration tokens above remain correct for screen transitions (enter/exit/shared-axis).

**Migration path**: When adding spring motion, replace `animateXAsState(tween(...))` with `animateXAsState(spring(dampingRatio, stiffness))`. Keep `tween()` for route transitions and coordinated multi-element sequences.

### MD3 Duration Scale Reference

The full M3 duration scale, for reference when mapping RIPDPI tokens:

| MD3 Token | Value | RIPDPI Mapping |
|-----------|-------|----------------|
| Short 1 | 50ms | -- |
| Short 2 | 100ms | -- |
| Short 3 | 150ms | `quickDurationMillis` (120ms, close) |
| Short 4 | 200ms | -- |
| Medium 1 | 250ms | `stateDurationMillis` (220ms, close) |
| Medium 2 | 300ms | `routeDurationMillis` (260ms, close) |
| Medium 3 | 350ms | `emphasizedDurationMillis` (320ms, close) |
| Medium 4 | 400ms | -- |
| Long 1-4 | 450-600ms | -- (not used; RIPDPI keeps transitions snappy) |

RIPDPI durations are intentionally shorter than MD3 defaults to match the app's utilitarian feel.

### Shape Morphing (M3 Expressive)

Components can morph between corner radii on interaction (press, select, loading). Example: a button at `xl` (16dp) morphing to `xlIncreased` (20dp) on press.

**Adoption status**: Shape morphing requires `@ExperimentalMaterial3ExpressiveApi`. RIPDPI does not currently use shape morphing. When adopting, implement via `animateShape()` or animated `RoundedCornerShape` with spring easing.

### Motion behavior

- `animationsEnabled = false`: all durations become 0.
- `reducedMotion = true`: durations halve (min 80ms), infinite animations disabled, spring animations use critically-damped (no bounce).
- `allowsInfiniteMotion`: only when `animationsEnabled && !reducedMotion`.
- Preview/inspection mode and `ripdpi.staticMotion` system property disable all animations.
- System animator setting (`ValueAnimator.areAnimatorsEnabled()`) is respected.

---

## 9. Icon Rules

- All icons imported via `RipDpiIcons` object -- never import raw Material icons in screens.
- Icon language: **outlined Material only**.
- Exceptions: app assets and illustration drawings (not action/navigation icons).
- Icon sizes: `RipDpiIconSizes.Default` for standard, `RipDpiIconSizes.Small` for compact contexts.

---

## 10. Accessibility Baselines

- Interactive hit area: **minimum 48dp** (enforced via `controlHeight`, `buttonMinHeight`, `iconButtonSize`, `switchHeight`).
- Disabled alpha: **0.38** (buttons, text fields, switches, chips).
- Icon-only buttons: must have `contentDescription`.
- Text fields: expose `label` via `contentDescription`, `errorText` via `error` semantics.
- Switches: expose `stateDescription` ("On"/"Off") and `contentDescription` from label.
- Selectable cards (`PresetCard`): must expose selection state semantically, not only visually.
- Status indicators: merge descendants with combined `contentDescription`.
- Secondary text roles: must remain readable in both light and dark themes.

---

## 11. Do's and Don'ts

### Do

- Use `RipDpiThemeTokens.colors.*` for all colors.
- Use `RipDpiThemeTokens.type.*` for all text styles.
- Use `RipDpiThemeTokens.spacing.*` for all spacing.
- Use `RipDpiThemeTokens.components.*` for all control metrics.
- Use existing scaffolds (`RipDpiContentScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`, `RipDpiIntroScaffold`) for every screen.
- Import icons only from `RipDpiIcons`.
- Keep feature-specific metrics in the screen family that owns them (e.g., `HomeChromeMetrics`).
- Wrap text fields with `RipDpiTextField` or `RipDpiConfigTextField`.
- Use `RipDpiCard` with the appropriate variant instead of raw Material Card.
- Animate state changes with `animateColorAsState` + `motion.duration(motion.stateDurationMillis)`.
- Use weight promotion (400->500->700) for emphasis instead of increasing font size (M3 Expressive).
- Use Expressive shape tokens (`xlIncreased`, `xxlIncreased`, `xxxl`) for new interactive surfaces and morphed states.
- Prefer `spring()` over `tween()` for new component-level animations once the Expressive API stabilizes.
- Use `bodyEmphasis` for selected/active/unread text states.

### Don't

- Don't hardcode colors or spacing when an existing token covers the need.
- Don't import raw Material icons into screens -- add to `RipDpiIcons` first.
- Don't add feature-local geometry to global theme tokens.
- Don't introduce a new scaffold without reusing an existing one or documenting the exception.
- Don't use raw `MaterialTheme.colorScheme.error` -- use `colors.destructive`.
- Don't use gradients, decorative shadows, or brand colors beyond the pixel brand mark.
- Don't override typography inline for branding -- create a scoped component.
- Don't create interactive elements smaller than 48dp hit area.
- Don't use `@ExperimentalMaterial3ExpressiveApi` APIs without checking the adoption status table in section 15.
- Don't increase font size to create emphasis -- use weight promotion instead (M3 Expressive principle).
- Don't mix spring and tween animations on the same element -- pick one motion model per component.

---

## 12. Screen Composition Pattern

Every screen follows a three-layer architecture:

```
Route composable         -- Navigation entry point, collects ViewModel
  Screen composable      -- Scaffold + state wiring, receives UiState
    Section composables  -- Content blocks, pure UI
```

### State management

- ViewModel exposes `UiState` (data class) via `StateFlow`.
- Side effects via `Effect` sealed class, collected with `LaunchedEffect`.
- User actions dispatched as `Event` sealed class.
- One ViewModel per screen family.

### Navigation

- Routes defined as enum entries in the navigation graph.
- `RipDpiNavHost` handles route transitions with `routeDurationMillis` timing.
- Bottom nav uses `BottomNavBar` with indicator animation.

---

## 13. Agent Prompt Guide

### Quick color reference

```
Primary action button:    foreground / background
Secondary action button:  secondary / onSecondary
Card surface:             surface (Material) / cardForeground
Page background:          background
Secondary text:           mutedForeground
Borders:                  border (heavy), cardBorder (card), outlineVariant (input)
Success:                  success (#047857 light / #34D399 dark)
Warning:                  warning (#B45309 light / #FBBF24 dark)
Error:                    destructive (#B91C1C light / #F87171 dark)
Info:                     info (#1D4ED8 light / #60A5FA dark)
```

### Ready-to-use component prompts

**New settings screen**: Use `RipDpiSettingsScaffold` with `RipDpiIcons.Back` navigation. Group settings in `RipDpiCard` containers. Use `SettingsRow` for each setting with `showDivider = true` between rows. Toggle settings use `checked`/`onCheckedChange`. Navigation settings use `value`/`onClick`/`showChevron`.

**New content screen**: Use `RipDpiContentScreenScaffold` with appropriate `contentWidth`. Sections spaced by `layout.sectionGap`. Use `sectionTitle` style for headers.

**New dashboard card**: Use `RipDpiCard(variant = RipDpiCardVariant.Status)` for status cards. Use `StatusIndicator` with appropriate tone. Values in `monoValue`, labels in `caption`.

**Form inputs**: Use `RipDpiTextField` for single values, `RipDpiConfigTextField` for config strings. Always provide `label` and `placeholder`. Show `errorText` for validation failures. Use `monoValue` for numeric inputs, `monoConfig` for string configs.

**Filter row**: Use `RipDpiChip` in a horizontal `Row` with `Arrangement.spacedBy(spacing.sm)`. Track selection state per chip. Selected chips invert colors and show check icon.

### File locations

| What | Path |
|------|------|
| Theme tokens | `app/src/main/kotlin/.../ui/theme/` |
| Components | `app/src/main/kotlin/.../ui/components/` |
| Screens | `app/src/main/kotlin/.../ui/screens/` |
| Screenshot baselines | `app/src/test/screenshots/` |
| Design system docs | `docs/design-system.md` |

---

## 14. Screenshot Testing

Verify visual changes with Roborazzi:

```bash
./gradlew recordScreenshots    # Record new baselines
./gradlew verifyScreenshots    # Verify against baselines
```

Catalogs:
- `RipDpiDesignSystemScreenshotCatalog` -- component variants (compact, dark-medium, large-font)
- `RipDpiScreenPreviewCatalog` -- screen scenes (home, settings, intro/auth)

Animated and focus-driven samples are excluded from screenshot catalogs.

---

## 15. M3 Expressive Adoption Status

This section tracks RIPDPI's alignment with the Material 3 Expressive update (May 2025).

### Adopted

| Feature | Status | Notes |
|---------|--------|-------|
| M3 easing curves | Fully adopted | `EmphasizedDecelerate`, `EmphasizedAccelerate`, `StandardEasing` in `RipDpiMotion` |
| Interaction scales | Fully adopted | `pressScale` (0.98), `selectionScale` (1.02), `emphasisScale` (1.04) |
| Expressive shape tokens | Tokens defined | `xlIncreased` (20dp), `xxlIncreased` (32dp), `xxxl` (48dp) added to `RipDpiShapeTokens` |
| Emphasized typography | Convention defined | Weight promotion (400->500->700) via `bodyEmphasis` and inline overrides |
| Button size scale | Mapped | XS-XL mapped to RIPDPI density system; default remains 48dp |
| Reduced motion | Fully adopted | Durations halve, infinite animations disabled, respects system setting |

### Deferred

| Feature | Reason | Adopt when |
|---------|--------|------------|
| Spring-based motion (`spring()`) | `@ExperimentalMaterial3ExpressiveApi` | API stabilizes in Compose BOM |
| Shape morphing | Experimental, requires `animateShape()` | API stabilizes; start with button press morph |
| 3 contrast levels (standard/medium/high) | Requires `ColorScheme` contrast parameter | User demand or accessibility audit requires it |
| `MaterialExpressiveTheme` | Experimental wrapper | Becomes stable in Compose Material3 |
| Toggle buttons | Covered by `RipDpiChip` and `RipDpiIconButton` toggle | If distinct toggle-button UX is needed |

### Contrast Levels

M3 Expressive introduces 3 user-controlled contrast levels:

| Level | Effect | RIPDPI approach |
|-------|--------|-----------------|
| Standard | Default tonal pairing | Current behavior |
| Medium | Increased container/content contrast | Not yet exposed; achievable via `RipDpiExtendedColors` variant |
| High | Maximum contrast, thicker borders | Not yet exposed; useful for accessibility settings |

When adopting, expose contrast as a user preference in Settings and generate adjusted `RipDpiExtendedColors` palettes per level. Do not hardcode high-contrast overrides in individual components.
