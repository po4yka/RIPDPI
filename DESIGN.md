---
version: alpha
name: RIPDPI
description: Monochrome-first Android VPN utility UI with compact operator tooling and restrained status color.
colors:
  primary: "#1A1A1A"
  secondary: "#444444"
  tertiary: "#E8E8E8"
  neutral: "#FAFAFA"
  surface: "#FFFFFF"
  surface-variant: "#F0F0F0"
  outline: "#757575"
  outline-variant: "#D0D0D0"
  success: "#047857"
  warning: "#B45309"
  warning-container: "#FFF7ED"
  destructive: "#B91C1C"
  destructive-container: "#FEF2F2"
  info: "#1D4ED8"
  info-container: "#EFF6FF"
  restricted: "#6B7280"
  restricted-container: "#F3F4F6"
typography:
  screen-title:
    fontFamily: Geist Sans
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  app-bar-title:
    fontFamily: Geist Sans
    fontSize: 20px
    fontWeight: 500
    lineHeight: 28px
  section-title:
    fontFamily: Geist Sans
    fontSize: 13px
    fontWeight: 500
    lineHeight: 18px
    letterSpacing: 0.72px
  body:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  body-emphasis:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 500
    lineHeight: 20px
  caption:
    fontFamily: Geist Sans
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
  button:
    fontFamily: Geist Sans
    fontSize: 15px
    fontWeight: 500
    lineHeight: 20px
  mono-inline:
    fontFamily: Geist Mono
    fontSize: 13px
    fontWeight: 400
    lineHeight: 20px
  mono-log:
    fontFamily: Geist Mono
    fontSize: 12px
    fontWeight: 400
    lineHeight: 20px
  brand-mark:
    fontFamily: Geist Pixel Circle
    fontSize: 32px
    fontWeight: 400
    lineHeight: 48px
    letterSpacing: 0.8px
rounded:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 28px
  hero: 48px
  full: 999px
spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 20px
  xxl: 24px
  xxxl: 32px
  section: 40px
  screen: 48px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    typography: "{typography.button}"
    rounded: "{rounded.lg}"
    height: 48px
    padding: 20px
  button-destructive:
    backgroundColor: "{colors.destructive}"
    textColor: "{colors.surface}"
    typography: "{typography.button}"
    rounded: "{rounded.lg}"
    height: 48px
    padding: 20px
  input-default:
    backgroundColor: "{colors.surface-variant}"
    textColor: "{colors.primary}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    height: 48px
    padding: 16px
  card-default:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    rounded: "{rounded.lg}"
    padding: 16px
  screen-default:
    backgroundColor: "{colors.neutral}"
    textColor: "{colors.primary}"
    padding: 48px
  label-secondary:
    backgroundColor: "{colors.neutral}"
    textColor: "{colors.secondary}"
    typography: "{typography.body}"
    padding: 4px
  surface-muted:
    backgroundColor: "{colors.tertiary}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 12px
  rule-strong:
    backgroundColor: "{colors.outline}"
    height: 1px
    width: 48px
  rule-subtle:
    backgroundColor: "{colors.outline-variant}"
    height: 1px
    width: 48px
  status-success:
    backgroundColor: "{colors.success}"
    textColor: "{colors.surface}"
    rounded: "{rounded.full}"
    padding: 8px
  status-warning:
    backgroundColor: "{colors.warning}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: 8px
  banner-warning:
    backgroundColor: "{colors.warning-container}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 16px
  banner-destructive:
    backgroundColor: "{colors.destructive-container}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 16px
  status-info:
    backgroundColor: "{colors.info}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: 8px
  banner-info:
    backgroundColor: "{colors.info-container}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 16px
  status-restricted:
    backgroundColor: "{colors.restricted}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: 8px
  banner-restricted:
    backgroundColor: "{colors.restricted-container}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: 16px
---

# RIPDPI Design

## Overview

RIPDPI is an operator-facing Android utility, not a lifestyle product. The interface should feel quiet,
deliberate, and technically trustworthy. The default tone is monochrome-first: dense information, strong
contrast, minimal ornament, and only narrow use of semantic status color where the state itself matters.

The UI should read like instrumentation rather than marketing. Surfaces stay matte and restrained. Controls
are crisp, touch-safe, and stable under large font sizes. Dark mode mirrors the same hierarchy instead of
introducing a different visual identity.

## Colors

The palette is intentionally conservative.

- **Primary (`#1A1A1A`)**: the core ink for primary text, key actions, and visual anchors.
- **Secondary (`#444444`)**: subdued utility text and structural support where full ink would feel heavy.
- **Tertiary (`#E8E8E8`)**: a pale support tone for containers and subdued emphasis.
- **Neutral (`#FAFAFA`)**: the app canvas; it should feel clean but not stark.
- **Surface (`#FFFFFF`)** and **Surface Variant (`#F0F0F0`)**: layered content backgrounds with subtle separation.
- **Outline (`#757575`)** and **Outline Variant (`#D0D0D0`)**: durable borders and dividers.

Status colors are reserved for diagnostics, warnings, destructive actions, and system health:

- **Success (`#047857`)** for healthy or active confirmation.
- **Warning (`#B45309`)** and **Warning Container (`#FFF7ED`)** for caution states.
- **Destructive (`#B91C1C`)** and **Destructive Container (`#FEF2F2`)** for resets, failures, and danger.
- **Info (`#1D4ED8`)** and **Info Container (`#EFF6FF`)** for explanatory state.
- **Restricted (`#6B7280`)** and **Restricted Container (`#F3F4F6`)** for constrained or unavailable modes.

Dark mode should preserve the same semantic hierarchy. It inverts contrast and surface depth, but it should
not become colorful or glossy.

## Typography

Typography uses bundled Geist families.

- **Geist Sans** is the default UI face for titles, labels, and body text.
- **Geist Mono** is reserved for logs, config fragments, strategy labels, and dense technical values.
- **Geist Pixel Circle** is reserved for the home brand mark only.

Text should stay compact and readable. Screen and app-bar titles are medium-weight rather than oversized.
Section labels are small, tightly structured, and slightly tracked for grouping. Monospace text should feel
operator-oriented, never decorative.

## Layout

The layout uses a 4px-derived spacing system with practical Android touch targets.

- Small steps (`4px`, `8px`, `12px`, `16px`) drive local control spacing.
- Larger steps (`20px`, `24px`, `32px`, `40px`, `48px`) structure sections and screens.
- Interactive controls should preserve a minimum visible height of `48px`.

RIPDPI is adaptive. Compact layouts should feel efficient, not cramped. Wider layouts should center or split
content instead of stretching a single column edge to edge. Large font scale must remain supported without
clipping or collapsing hierarchy.

## Shapes

Corners are rounded but restrained.

- Small utility controls can use `4px` to `8px` radii.
- Standard controls and cards typically use `12px` to `16px`.
- Pills and segmented controls can use `28px` or fully rounded treatments.
- Hero or brand surfaces may use a larger `48px` radius, but only when the screen family already supports it.

The shape language should feel precise and engineered, not playful.

## Components

Core components should follow a simple hierarchy:

- **Primary buttons** use dark ink fills with light text and a clear `48px` minimum height.
- **Destructive buttons** use the destructive red only when the action is actually dangerous.
- **Inputs** use muted container fills rather than stark outlined-only treatment.
- **Cards** stay flat and quiet, relying on subtle borders and surface separation instead of heavy elevation.
- **Warning and info banners** use pale semantic containers with strong readable foreground text.

Disabled, focused, pressed, error, and selected states must remain visible. Icon-only actions require an
accessible label. Monospace treatments belong in data-heavy or log-heavy surfaces, not in general navigation.

## Do's and Don'ts

Do:

- keep the default UI monochrome-first
- use semantic status color sparingly and intentionally
- rely on shared tokens before introducing local visual values
- preserve readable hierarchy in both light and dark themes
- keep diagnostics and logs feeling dense, technical, and stable

Don't:

- turn the app into a gradient-heavy consumer dashboard
- use bright accent colors for routine actions
- hardcode one-off colors, spacing, or icon styles in screens
- use branding typography outside the reserved home-brand surface
- rely on color alone to communicate warnings, selection, or disabled state
