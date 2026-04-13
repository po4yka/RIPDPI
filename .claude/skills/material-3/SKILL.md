---
name: material-3
description: >
  Implement Google's Material Design 3 (Material You) UI system. Covers design tokens
  (color, typography, shape, elevation, motion), 30+ components, responsive layout,
  dynamic color theming, and accessibility. Supports web (@material/web), Flutter,
  and Jetpack Compose. Use when: "material design", "MD3", "material you",
  "material component", "material theme", "@material/web", "md3 button",
  "material card", "material navigation".
user-invokable: true
argument-hint: "[component|theme|layout|scaffold|audit] [description or URL]"
---

# Material Design 3

This skill guides implementation of Google's Material Design 3 (MD3) — a personal, adaptive, expressive design system. MD3 uses dynamic color, tonal surfaces, rounded shapes, and spring-based motion to create UIs that feel alive and personal.

## Philosophy

MD3 is built on three principles:
- **Personal**: Dynamic color adapts UI to the user's wallpaper or content. Theming is individual, not one-size-fits-all.
- **Adaptive**: Layouts transform across 5 window size classes. Components resize, reposition, and change form factor responsively.
- **Expressive**: Shape morphing, spring physics, and emphasized typography create moments of delight without sacrificing usability.

**Key differences from MD2:**
- Tonal surfaces replace elevation shadows as the primary depth cue
- Dynamic color generates full schemes from a single seed color
- Fully rounded corners by default (not slightly rounded)
- Spring-based motion physics replace fixed easing curves for components
- 3 levels of user-controlled contrast (standard/medium/high)

**Relationship with frontend-design skill:**
When both skills are active, MD3 provides the design system (tokens, components, layout rules) and frontend-design provides creative direction within those constraints. MD3 rules take precedence for component structure and token usage. Note: Roboto/Roboto Flex IS the correct default typeface in MD3 — the frontend-design guidance to avoid Roboto does not apply when implementing MD3.

## Decision Tree

**What are you building?**
```
Full app scaffold        → See "Common Patterns: App Shell" + references/layout-and-responsive.md
Single component         → See "Component Quick Reference" table → references/component-catalog.md
Custom theme             → See references/theming-and-dynamic-color.md
Form / input layout      → See references/component-catalog.md § Input Components
Navigation structure     → See references/navigation-patterns.md
Data display             → See references/component-catalog.md § Data Display
```

**What platform?**
```
Web (vanilla JS)         → @material/web components + CSS custom properties
Web (React/Vue/Svelte)   → CSS custom properties + wrapper components (no official React lib)
Web (CSS-only)           → Use MD3 token values as CSS custom properties (no <md-*> elements)
Flutter                  → material3: true in ThemeData, ColorScheme.fromSeed()
Jetpack Compose          → MaterialTheme with Material3 dependencies
```

## Design Token System

All MD3 tokens use the `md.sys` namespace. On the web, these map to CSS custom properties:

### Color Tokens (`--md-sys-color-*`)
| Token | Purpose |
|-------|---------|
| `primary` | High-emphasis fills, text, icons against surface |
| `on-primary` | Text/icons on primary |
| `primary-container` | Standout fill for key components (FAB, etc.) |
| `on-primary-container` | Text/icons on primary-container |
| `secondary` / `on-secondary` | Less prominent accents |
| `secondary-container` / `on-secondary-container` | Recessive components (tonal buttons) |
| `tertiary` / `on-tertiary` | Contrasting accents |
| `tertiary-container` / `on-tertiary-container` | Complementary containers |
| `error` / `on-error` | Error states (static — doesn't change with dynamic color) |
| `error-container` / `on-error-container` | Error container fills |
| `surface` | Default background |
| `on-surface` | Text/icons on any surface |
| `on-surface-variant` | Lower-emphasis text/icons on surface |
| `surface-container-lowest` | Lowest-emphasis container |
| `surface-container-low` | Low-emphasis container |
| `surface-container` | Default container (nav areas) |
| `surface-container-high` | High-emphasis container |
| `surface-container-highest` | Highest-emphasis container |
| `surface-dim` / `surface-bright` | Maintain relative brightness across light/dark |
| `inverse-surface` / `inverse-on-surface` / `inverse-primary` | Contrasting elements (snackbars) |
| `outline` | Important boundaries (text field borders) |
| `outline-variant` | Decorative elements (dividers) |

Full details: `references/color-system.md`

### Typography Tokens (`--md-sys-typescale-*`)
| Scale | Sizes | Use |
|-------|-------|-----|
| Display | L / M / S | Hero text, large numbers |
| Headline | L / M / S | Section headers |
| Title | L / M / S | Smaller headers, card titles |
| Body | L / M / S | Paragraph text, descriptions |
| Label | L / M / S | Buttons, chips, captions |

Each style has tokens for: `-font`, `-weight`, `-size`, `-line-height`, `-tracking`
Plus 15 **emphasized** variants (higher weight) via `--md-sys-typescale-emphasized-*`

Full details: `references/typography-and-shape.md`

### Shape Tokens (`--md-sys-shape-corner-*`)
| Token | Value | Example components |
|-------|-------|-------------------|
| `none` | 0dp | — |
| `extra-small` | 4dp | Chips, snackbars |
| `small` | 8dp | Text fields, menus |
| `medium` | 12dp | Cards, dialogs |
| `large` | 16dp | FABs, navigation drawer |
| `large-increased` | 20dp | (Expressive) |
| `extra-large` | 28dp | Bottom sheets |
| `extra-large-increased` | 32dp | (Expressive) |
| `extra-extra-large` | 48dp | (Expressive) |
| `full` | 9999px | Buttons, chips, badges |

### Elevation Levels
| Level | DP | Tonal offset | Use |
|-------|-----|-------------|-----|
| 0 | 0dp | None | Flat surfaces, most components at rest |
| 1 | 1dp | +5% primary | Elevated cards, modal sheets |
| 2 | 3dp | +8% primary | Menus, nav bar, scrolled app bar |
| 3 | 6dp | +11% primary | FAB, dialogs, search, date/time pickers |
| 4 | 8dp | +12% primary | (hover/focus increase only) |
| 5 | 12dp | +14% primary | (hover/focus increase only) |

Elevation in MD3 is communicated through **tonal surface color**, not shadows. Shadows are only used when needed for additional protection against busy backgrounds.

### Motion
MD3 Expressive (May 2025) introduced **spring-based motion physics** for components. The legacy easing/duration system is still used for **transitions** (enter/exit/shared-axis):

| Easing | Duration | Transition type |
|--------|----------|-----------------|
| Emphasized | 500ms | Begin and end on screen |
| Emphasized decelerate | 400ms | Enter the screen |
| Emphasized accelerate | 200ms | Exit the screen |
| Standard | 300ms | Begin and end on screen (utility) |
| Standard decelerate | 250ms | Enter screen (utility) |
| Standard accelerate | 200ms | Exit screen (utility) |

CSS easing values:
- Emphasized: `cubic-bezier(0.2, 0, 0, 1)`
- Emphasized decelerate: `cubic-bezier(0.05, 0.7, 0.1, 1)`
- Emphasized accelerate: `cubic-bezier(0.3, 0, 0.8, 0.15)`
- Standard: `cubic-bezier(0.2, 0, 0, 1)`
- Standard decelerate: `cubic-bezier(0, 0, 0, 1)`
- Standard accelerate: `cubic-bezier(0.3, 0, 1, 1)`

## Component Quick Reference

| Component | Web Element | Key Variants | Category |
|-----------|------------|--------------|----------|
| Button | `md-filled-button`, `md-outlined-button`, `md-text-button`, `md-elevated-button`, `md-filled-tonal-button` | Filled, Outlined, Text, Elevated, Tonal; 5 sizes (XS–XL); toggle | Actions |
| Button group | `md-button-group` | Standard, connected | Actions |
| Extended FAB | `md-extended-fab` | Surface, Primary, Secondary, Tertiary | Actions |
| FAB | `md-fab` | Small, Medium, Large | Actions |
| FAB menu | — | — | Actions |
| Icon button | `md-icon-button`, `md-filled-icon-button`, `md-filled-tonal-icon-button`, `md-outlined-icon-button` | Standard, Filled, Filled Tonal, Outlined | Actions |
| Segmented button | — | Single-select, Multi-select | Actions |
| Split button | — | — | Actions |
| Badge | — | Small (dot), Large (count) | Communication |
| Loading indicator | — | Linear, Circular | Communication |
| Progress indicator | `md-linear-progress`, `md-circular-progress` | Linear, Circular; determinate/indeterminate | Communication |
| Snackbar | — | Single-line, Two-line, Action | Communication |
| Tooltip | — | Plain, Rich | Communication |
| Card | — | Filled, Outlined, Elevated | Containment |
| Carousel | — | Multi-browse, Uncontained, Hero | Containment |
| Dialog | `md-dialog` | Basic, Full-screen | Containment |
| Bottom sheet | — | Standard, Modal | Sheets |
| Side sheet | — | Standard, Modal | Sheets |
| Divider | `md-divider` | Full-width, Inset | Containment |
| Checkbox | `md-checkbox` | — | Input |
| Chips | `md-chip-set`, `md-assist-chip`, `md-filter-chip`, `md-input-chip`, `md-suggestion-chip` | Assist, Filter, Input, Suggestion | Input |
| Date picker | — | Docked, Modal, Range | Input |
| Menu | `md-menu`, `md-menu-item` | — | Input |
| Radio button | `md-radio` | — | Input |
| Slider | `md-slider` | Continuous, Discrete, Range | Input |
| Switch | `md-switch` | With/without icon | Input |
| Text field | `md-filled-text-field`, `md-outlined-text-field` | Filled, Outlined | Input |
| Time picker | — | Docked, Modal | Input |
| App bar (top) | — | Center-aligned, Small, Medium, Large | Navigation |
| Navigation bar | `md-navigation-bar` | — | Navigation |
| Navigation drawer | `md-navigation-drawer` | Standard, Modal | Navigation |
| Navigation rail | — | — | Navigation |
| Search | — | Search bar, Search view | Navigation |
| Tabs | `md-tabs`, `md-primary-tab`, `md-secondary-tab` | Primary, Secondary | Navigation |
| Toolbar | — | — | Navigation |
| List | `md-list`, `md-list-item` | One-line, Two-line, Three-line | Data Display |

**Note:** Components marked with `—` for web element don't have @material/web implementations yet. Use CSS custom properties with standard HTML for these.

Full component details with code examples: `references/component-catalog.md`

## Web Implementation with @material/web

### Setup

```bash
npm install @material/web
```

### Import Components Individually

Always import only the components you use — importing the entire package bloats the bundle:

```javascript
// Good — individual imports
import '@material/web/button/filled-button.js';
import '@material/web/button/outlined-button.js';
import '@material/web/textfield/outlined-text-field.js';
import '@material/web/icon/icon.js';

// Bad — never do this
import '@material/web'; // imports everything
```

### Basic Usage

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link href="https://fonts.googleapis.com/css2?family=Roboto+Flex:wght@400;500;700&display=swap" rel="stylesheet">
  <link href="https://fonts.googleapis.com/icon?family=Material+Symbols+Outlined" rel="stylesheet">
</head>
<body>
  <md-filled-button>Get started</md-filled-button>
  <md-outlined-text-field label="Email" type="email"></md-outlined-text-field>

  <script type="module">
    import '@material/web/button/filled-button.js';
    import '@material/web/textfield/outlined-text-field.js';
  </script>
</body>
</html>
```

### Theming with CSS Custom Properties

Apply a custom theme by setting CSS custom properties on `:root` or any ancestor:

```css
:root {
  /* Color scheme (generate with @material/material-color-utilities) */
  --md-sys-color-primary: #6750A4;
  --md-sys-color-on-primary: #FFFFFF;
  --md-sys-color-primary-container: #EADDFF;
  --md-sys-color-on-primary-container: #21005D;
  --md-sys-color-secondary: #625B71;
  --md-sys-color-on-secondary: #FFFFFF;
  --md-sys-color-secondary-container: #E8DEF8;
  --md-sys-color-on-secondary-container: #1D192B;
  --md-sys-color-surface: #FEF7FF;
  --md-sys-color-on-surface: #1D1B20;
  --md-sys-color-surface-container: #F3EDF7;
  --md-sys-color-outline: #79747E;
  --md-sys-color-outline-variant: #CAC4D0;

  /* Typography */
  --md-sys-typescale-body-large-font: 'Roboto Flex', sans-serif;
  --md-sys-typescale-body-large-size: 1rem;
  --md-sys-typescale-body-large-weight: 400;
  --md-sys-typescale-body-large-line-height: 1.5rem;

  /* Shape */
  --md-sys-shape-corner-full: 9999px;
  --md-sys-shape-corner-medium: 12px;
}
```

### Component-Level Overrides

Override individual component tokens for specific customization:

```css
md-filled-button {
  --md-filled-button-container-color: var(--md-sys-color-primary);
  --md-filled-button-label-text-color: var(--md-sys-color-on-primary);
  --md-filled-button-container-shape: var(--md-sys-shape-corner-full);
  --md-filled-button-container-height: 40px;
}

md-outlined-text-field {
  --md-outlined-text-field-container-shape: var(--md-sys-shape-corner-small);
  --md-outlined-text-field-focus-outline-color: var(--md-sys-color-primary);
}
```

### Dark Theme

Apply dark theme by overriding color tokens on a class or media query:

```css
@media (prefers-color-scheme: dark) {
  :root {
    --md-sys-color-primary: #D0BCFF;
    --md-sys-color-on-primary: #381E72;
    --md-sys-color-primary-container: #4F378B;
    --md-sys-color-on-primary-container: #EADDFF;
    --md-sys-color-surface: #141218;
    --md-sys-color-on-surface: #E6E0E9;
    --md-sys-color-surface-container: #211F26;
    --md-sys-color-outline: #938F99;
    --md-sys-color-outline-variant: #49454F;
  }
}
```

Full theming guide: `references/theming-and-dynamic-color.md`

## Common Patterns

### App Shell

Standard MD3 app with responsive navigation + top app bar + content area:

```html
<div class="md3-app">
  <nav class="md3-nav-rail" aria-label="Main navigation">
    <!-- Navigation rail for medium+ screens -->
    <md-fab size="small" aria-label="Compose">
      <md-icon slot="icon">edit</md-icon>
    </md-fab>
    <md-navigation-bar>
      <md-navigation-tab label="Home">
        <md-icon slot="active-icon">home</md-icon>
        <md-icon slot="inactive-icon">home</md-icon>
      </md-navigation-tab>
      <md-navigation-tab label="Search">
        <md-icon slot="active-icon">search</md-icon>
        <md-icon slot="inactive-icon">search</md-icon>
      </md-navigation-tab>
    </md-navigation-bar>
  </nav>
  <main class="md3-content">
    <header class="md3-top-app-bar">
      <h1 class="md3-top-app-bar__title" style="font: var(--md-sys-typescale-title-large)">
        Page Title
      </h1>
    </header>
    <div class="md3-body">
      <!-- Content here -->
    </div>
  </main>
</div>
```

```css
.md3-app {
  display: flex;
  min-height: 100vh;
  background: var(--md-sys-color-surface);
  color: var(--md-sys-color-on-surface);
}

.md3-nav-rail {
  width: 80px;
  background: var(--md-sys-color-surface);
  border-right: 1px solid var(--md-sys-color-outline-variant);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 12px;
  gap: 12px;
}

.md3-content {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.md3-top-app-bar {
  height: 64px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  background: var(--md-sys-color-surface);
}

.md3-body {
  padding: 24px;
  flex: 1;
}

/* Responsive: switch to bottom nav on compact */
@media (max-width: 599px) {
  .md3-app { flex-direction: column; }
  .md3-nav-rail {
    order: 1;
    width: 100%;
    flex-direction: row;
    justify-content: center;
    border-right: none;
    border-top: 1px solid var(--md-sys-color-outline-variant);
    padding: 0;
  }
}
```

### Card Grid

```html
<div class="md3-card-grid">
  <div class="md3-card md3-card--outlined">
    <img src="image.jpg" alt="Description" class="md3-card__media">
    <div class="md3-card__content">
      <h3 style="font: var(--md-sys-typescale-title-medium)">Card Title</h3>
      <p style="font: var(--md-sys-typescale-body-medium); color: var(--md-sys-color-on-surface-variant)">
        Supporting text for this card.
      </p>
    </div>
    <div class="md3-card__actions">
      <md-text-button>Learn more</md-text-button>
      <md-filled-tonal-button>Action</md-filled-tonal-button>
    </div>
  </div>
</div>
```

```css
.md3-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.md3-card--outlined {
  border: 1px solid var(--md-sys-color-outline-variant);
  border-radius: var(--md-sys-shape-corner-medium, 12px);
  background: var(--md-sys-color-surface);
  overflow: hidden;
}

.md3-card__content { padding: 16px; }
.md3-card__actions { padding: 8px 16px 16px; display: flex; gap: 8px; justify-content: flex-end; }
.md3-card__media { width: 100%; aspect-ratio: 16/9; object-fit: cover; }
```

### Form Layout

```html
<form class="md3-form">
  <md-outlined-text-field label="Full name" required></md-outlined-text-field>
  <md-outlined-text-field label="Email" type="email" required></md-outlined-text-field>
  <md-outlined-text-field label="Message" type="textarea" rows="4"></md-outlined-text-field>
  <div class="md3-form__actions">
    <md-text-button type="reset">Cancel</md-text-button>
    <md-filled-button type="submit">Submit</md-filled-button>
  </div>
</form>
```

```css
.md3-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 560px;
}

.md3-form__actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 8px;
}
```

More patterns: `references/navigation-patterns.md`, `references/layout-and-responsive.md`

## Anti-Patterns

**Never do these when implementing MD3:**

- **Mix MD2 and MD3 libraries**: Don't use `@material/mdc-*` (MD2) alongside `@material/web` (MD3). They have incompatible APIs and styling.
- **Hardcode colors**: Always use `var(--md-sys-color-*)` tokens, never raw hex/rgb values. Hardcoded colors break dynamic theming, dark mode, and contrast adjustment.
- **Ignore tonal pairing**: Only combine colors in their intended pairs (e.g., `primary` + `on-primary`, `surface-container` + `on-surface`). Arbitrary pairings break contrast in dynamic color and high contrast modes.
- **Use `outline` for dividers**: Use `outline-variant` for dividers. `outline` is for important boundaries like text field borders.
- **Import all of @material/web**: Always import individual component modules. Barrel imports include every component and destroy bundle size.
- **Use `border-radius` directly**: Use shape tokens (`var(--md-sys-shape-corner-medium)`) so shapes stay consistent with theming.
- **Use shadows for elevation by default**: MD3 communicates elevation through tonal surface color, not shadows. Only add shadows when elements need extra separation from busy backgrounds.
- **Apply frontend-design "avoid Roboto" rule**: Roboto Flex is the intended MD3 typeface. It's correct here. Replace it only if intentionally customizing the type scale.
- **Assume SSR compatibility**: `@material/web` uses Web Components (custom elements) which require JavaScript to render. They won't produce meaningful HTML in SSR without additional hydration strategies.
- **Ignore foldables and large screens**: MD3 is designed for all screen sizes. Don't ship phone-only layouts — use canonical layouts, multi-pane at 600dp+, and test on foldable/tablet emulators. Place no interactive content across the fold/hinge.
- **Stretch content to fill wide screens**: On Large (1200dp+) and Extra-large (1600dp+) windows, constrain content to a max width (840–1040dp). Endless-width text lines are unreadable.

## Platform Notes

### Flutter
```dart
MaterialApp(
  theme: ThemeData(
    useMaterial3: true,
    colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
  ),
);
```

### Jetpack Compose
```kotlin
MaterialTheme(
    colorScheme = dynamicLightColorScheme(context), // or dynamicDarkColorScheme
    typography = Typography(),
    shapes = Shapes(),
) {
    // Content
}
```

### Component Name Mapping
| Concept | Web | Flutter | Compose |
|---------|-----|---------|---------|
| Filled button | `md-filled-button` | `FilledButton` | `Button` |
| Outlined text field | `md-outlined-text-field` | `OutlinedTextField` | `OutlinedTextField` |
| FAB | `md-fab` | `FloatingActionButton` | `FloatingActionButton` |
| Navigation bar | `md-navigation-bar` | `NavigationBar` | `NavigationBar` |
| Switch | `md-switch` | `Switch` | `Switch` |

## M3 Expressive (May 2025)

The Expressive update adds visual richness while maintaining usability:
- **Spring-based motion**: Components now use spring physics instead of fixed easing curves. More natural and responsive. (Easing/duration still used for transitions.)
- **Emphasized typography**: 15 new emphasized type styles for selection states, actions, and headlines. Higher weight than baseline.
- **Shape morphing**: Components can morph between shapes on interaction (press, select). Currently Compose-only; web unavailable.
- **New button sizes**: XS, S (default), M, L, XL with toggle (selection) support.
- **New corner radii**: large-increased (20dp), extra-large-increased (32dp), extra-extra-large (48dp).
- **3 contrast levels**: Standard, medium, and high contrast — user-controlled.

When targeting web, note that many Expressive features (shape morph, spring physics) aren't yet available in @material/web. Use the CSS easing/duration tokens as fallback.

## MD3 Compliance Audit

When invoked with `audit` as the argument (e.g., `/material-3 audit`), or when asked to audit/review MD3 compliance, analyze the target app or page and produce a compliance report.

### Audit Procedure

1. **Identify the target**: The user provides a URL (use browser tools to inspect), file paths (read source), or a running app.
2. **Inspect the following categories** and score each 0–10:

| Category | What to check |
|----------|--------------|
| **Color tokens** | Uses `--md-sys-color-*` tokens (not hardcoded hex). Proper tonal pairing (on-X with X). Dark mode support. No arbitrary color combinations that break contrast. |
| **Typography** | Uses MD3 type scale tokens. Correct scale usage (Display for heroes, Body for text, Label for buttons). Consistent font family. |
| **Shape** | Uses shape tokens for border-radius. Correct token per component (full for buttons, medium for cards). No raw pixel values. |
| **Elevation** | Tonal surface colors used instead of shadows. Correct elevation levels per component. Hover/focus raises by 1 level. |
| **Components** | Uses `@material/web` elements or correctly implements MD3 component specs. Correct variants for context. Proper slot usage. |
| **Layout** | Responsive breakpoints match MD3 (compact/medium/expanded/large/extra-large). Uses canonical layouts where appropriate. Proper margins and spacing. Multi-pane layouts on medium+ screens. Content constrained to readable widths on large screens. Foldable hinge avoidance if targeting foldables. |
| **Navigation** | Correct nav component for screen size (bar on mobile, rail on tablet, drawer on desktop). Responsive transitions. Hover states for pointer devices on large screens. |
| **Motion** | Transitions use MD3 easing/duration tokens. Appropriate easing type for transition direction (enter/exit/persist). |
| **Accessibility** | Color contrast meets 3:1 minimum (MD3 built-in). Proper ARIA labels. Keyboard navigation. Focus indicators. |
| **Theming** | Theme is applied via CSS custom properties. Supports dark mode. Dynamic color ready (tokens not hardcoded). Component-level overrides use proper token names. |

3. **Generate the report**:

```
# MD3 Compliance Audit Report

Target: [URL or file path]
Date: [date]
Overall Score: [X/100]

## Scores by Category
| Category       | Score | Status |
|----------------|-------|--------|
| Color tokens   | X/10  | [pass/warn/fail] |
| Typography     | X/10  | [pass/warn/fail] |
| Shape          | X/10  | [pass/warn/fail] |
| Elevation      | X/10  | [pass/warn/fail] |
| Components     | X/10  | [pass/warn/fail] |
| Layout         | X/10  | [pass/warn/fail] |
| Navigation     | X/10  | [pass/warn/fail] |
| Motion         | X/10  | [pass/warn/fail] |
| Accessibility  | X/10  | [pass/warn/fail] |
| Theming        | X/10  | [pass/warn/fail] |

## Critical Issues
[List items scoring 0-3 with specific file:line references and fixes]

## Warnings
[List items scoring 4-6 with recommendations]

## Passing
[List items scoring 7-10 with notes on what's done well]

## Recommended Fixes (Priority Order)
1. [Most impactful fix first]
2. ...
```

### Audit Methods

**For a live URL** (browser tools available):
- Navigate to the page with `mcp__claude-in-chrome__navigate`
- Read the page DOM with `mcp__claude-in-chrome__read_page`
- Extract CSS custom properties with `mcp__claude-in-chrome__javascript_tool`
- Check computed styles for MD3 token usage
- Test responsive behavior by resizing with `mcp__claude-in-chrome__resize_window`
- Screenshot at different breakpoints

**For source code** (file paths provided):
- Read HTML/JSX/template files for component usage
- Read CSS/SCSS files for token usage and hardcoded values
- Check imports for @material/web components
- Search for hardcoded colors, border-radius values, box-shadows
- Verify responsive breakpoints in media queries

**Quick checks** (grep patterns for common violations):
```
# Hardcoded colors (should use tokens)
grep -rn '#[0-9a-fA-F]\{3,8\}' --include='*.css' --include='*.scss'
grep -rn 'rgb\(|rgba\(' --include='*.css' --include='*.scss'

# Raw border-radius (should use shape tokens)
grep -rn 'border-radius:' --include='*.css' | grep -v 'var(--md-sys-shape'

# Raw box-shadow (MD3 uses tonal elevation)
grep -rn 'box-shadow:' --include='*.css'

# MD2 imports (should be @material/web)
grep -rn '@material/mdc-' --include='*.js' --include='*.ts'

# Missing dark mode
grep -rn 'prefers-color-scheme' --include='*.css'
```

### Scoring Guide

- **9-10**: Fully MD3 compliant, uses correct tokens and patterns
- **7-8**: Mostly compliant, minor issues (e.g., a few hardcoded values)
- **4-6**: Partially compliant, some MD3 patterns but significant gaps
- **1-3**: Major violations, mostly non-MD3 or MD2 patterns
- **0**: Not applicable or completely absent

Status thresholds: **pass** (7+), **warn** (4-6), **fail** (0-3)

## Reference Documents

- `references/color-system.md` — Complete color role catalog, tonal palettes, dynamic color, light/dark scheme mapping
- `references/typography-and-shape.md` — Type scale values, shape corner scale, elevation levels, motion tokens
- `references/component-catalog.md` — All 30+ components with web element names, attributes, code examples, a11y notes
- `references/navigation-patterns.md` — Which navigation component to use, responsive nav transitions
- `references/layout-and-responsive.md` — Breakpoints, canonical layouts, CSS Grid implementation
- `references/theming-and-dynamic-color.md` — Theme generation, brand color integration, dark mode, runtime switching
