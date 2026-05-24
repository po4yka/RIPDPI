# RIPDPI · Android UI kit

Interactive, pixel-fidelity recreation of the live Compose UI tree at
`app/src/main/kotlin/com/poyka/ripdpi/ui/components/` and
`ui/screens/`. The visuals come from the same token contract as
production — the wiring is cheap fakes.

## What's inside

```
index.html               ← Phone frame + 6 screens + control panel
RipDpiComponents.jsx     ← Theme + every public composable analog
RipDpiScreens.jsx        ← Home / Diagnostics / Strategy / VPN config / Logs / Onboarding
android-frame.jsx        ← (unused, retained for reference) starter Android frame
```

`RipDpiComponents.jsx` exports `RDP` (the full token bundle), the
`RipDpiTheme` provider, and every component as a global so screens and
host code can pick them up without imports.

## Screens recreated

| Screen | Source of truth | What's clickable |
| --- | --- | --- |
| **Onboarding** | `ui/screens/onboarding/OnboardingScreen.kt` | `Continue` advances to Home |
| **Home** | `ui/screens/home/HomeScreen.kt` + `HomeModeCard.kt` + `HomeChrome.kt` | Connection actuator drag, mode-card actions, bottom nav |
| **Strategy config** | `ui/screens/settings/StrategyConfigScreen.kt` | Chip selection, text fields, switches, `Apply strategy` |
| **VPN config** | `ui/screens/config/VpnConfigScreen.kt` | Chip selection, settings rows, `Save profile` |
| **Diagnostics** | `ui/screens/diagnostics/DiagnosticsScreen.kt` | `Re-run scan` |
| **Logs** | `ui/screens/logs/LogsScreen.kt` | Level filter chips, `Reset cache` |

## Components recreated

| Component (JSX) | Source (Kotlin) |
| --- | --- |
| `RipDpiButton` (5 variants + loading + disabled) | `ui/components/buttons/RipDpiButton.kt` |
| `RipDpiIconButton` (4 variants) | `ui/components/buttons/RipDpiIconButton.kt` |
| `RipDpiCard` (Outlined/Tonal/Elevated/Status) | `ui/components/cards/RipDpiCard.kt` |
| `RipDpiChip` (selected, leading-check) | `ui/components/inputs/RipDpiChip.kt` |
| `RipDpiSwitch` (labeled / control) | `ui/components/inputs/RipDpiSwitch.kt` |
| `RipDpiTextField` (default / focused / error) | `ui/components/inputs/RipDpiTextField.kt` |
| `StatusIndicator` (4 tones, distinct primitives) | `ui/components/indicators/StatusIndicator.kt` |
| `WarningBanner` (4 tones) | `ui/components/feedback/WarningBanner.kt` |
| `MetricPill` (4 tones) | `ui/components/indicators/RipDpiMetricPill.kt` |
| `SettingsRow` + `SectionHeader` + `Hairline` | `ui/components/cards/SettingsRow.kt` + `navigation/SettingsCategoryHeader.kt` |
| `RipDpiTopAppBar` | `ui/components/navigation/RipDpiTopAppBar.kt` |
| `BottomNavBar` | `ui/navigation/BottomNavBar.kt` |
| `ConnectionActuator` (draggable) | `ui/components/inputs/RipDpiConnectionActuator.kt` |

## What's not recreated (deliberately)

- The full state-token machinery (`RipDpiButtonStateTokens` etc.) — the JSX
  components resolve states inline because there is no Compose composition
  to plug into.
- Compose `SpringSpec` — CSS easings approximate. Press-scale is a
  critically-damped 120ms transition rather than a true spring.
- Localization. Strings are English-only in this kit.
- The remediation ladder, the strategy probe suite UI (huge), the
  AmneziaWG profile editor — out of scope.

## Caveats

- Drag the actuator on a touch device; on a mouse, press and drag past 72%
  to activate / 28% to deactivate. Pointer-events are wired but there's no
  haptic feedback in the browser.
- Diagnostic and VPN-connect are timed fakes (1 s / 1.4 s).
- The Sim Disconnect button kills both bypass and VPN at once.
