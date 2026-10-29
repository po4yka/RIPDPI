# Change: Adopt the Material 3 theme foundation

Task ID: `UIX-1793256778917552`

## Why

Contrast levels exist only in previews, Material components animate with stock motion while RDS components use `RipDpiMotion`, the color scheme leaves many Material 3 roles at library defaults, and the app depends on deprecated icon artifacts.

## What Changes

- Seed-generated Material 3 color schemes for light and dark at three contrast levels; system contrast on Android 14 and later.
- Opt-in dynamic color setting (new AppSettings field, Settings switch, ten locales).
- Material motion scheme derived from `RipDpiMotion`.
- Complete Material 3 typography and shape scales.
- Material Symbols Outlined icons through `RipDpiIcons`; removal of `material-icons-core` and `material-icons-extended`.
- Design contract, project skills, and Glance widget theme alignment.

## Capabilities

### New Capabilities

- `m3-theme-foundation`: Material 3 color, contrast, dynamic color, motion, type, shape, and icon foundation.

### Modified Capabilities

- None.

## Impact

- `:app` theme, settings UI, icons, design docs, and project skills; `:core:data` AppSettings schema (one additive field); `gradle/libs.versions.toml`. Most screenshot fixtures change and need owner approval.
