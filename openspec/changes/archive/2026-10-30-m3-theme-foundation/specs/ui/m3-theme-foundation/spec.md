## Purpose

Define the Material 3 theme foundation that every RIPDPI screen and component uses.

## ADDED Requirements

### Requirement: REQ-UIX-M3F-COLOR — Complete seed color schemes

The app MUST provide light and dark Material 3 color schemes generated from one brand seed, each at standard, medium, and high contrast. Every Material 3 color role, including all surface container roles, MUST have an explicit value. Success, warning, info, and restricted MUST remain separate semantic roles.

#### Scenario: Inspect the scheme

- **WHEN** the theme resolves any of the six schemes
- **THEN** no Material 3 color role uses a library default and each text role meets its contrast pair

### Requirement: REQ-UIX-M3F-CONTRAST — System contrast level

On Android 14 and later the app MUST follow the system contrast level and MUST update while running when the user changes it. The level MUST apply to the Material color scheme and to the RDS extended colors. Older Android versions MUST use standard contrast.

#### Scenario: Change system contrast

- **WHEN** the user changes the system contrast level while the app is visible
- **THEN** Material-hosted and RDS components show the matching contrast level without a restart

### Requirement: REQ-UIX-M3F-DYNAMIC — Opt-in dynamic color

On Android 12 and later Settings MUST offer a dynamic color switch that is off by default. When it is on, the Material color roles MUST come from the system dynamic scheme and the status roles MUST stay unchanged. The value MUST persist in AppSettings and MUST survive backup and restore. On older versions the switch MUST NOT appear.

#### Scenario: Turn on dynamic color

- **WHEN** the user turns on the switch on Android 12 or later
- **THEN** the app uses wallpaper-based Material colors and keeps its status colors

### Requirement: REQ-UIX-M3F-MOTION — Shared motion scheme

`MaterialTheme` MUST receive a motion scheme derived from `RipDpiMotion`. When animations are disabled or reduced motion applies, Material components MUST NOT animate beyond the reduced RDS durations.

#### Scenario: Reduced motion

- **WHEN** system animations are off
- **THEN** Material tabs, sheets, and menus change state without animation

### Requirement: REQ-UIX-M3F-TYPE-SHAPE — Type and shape scales

Typography MUST define all fifteen baseline and fifteen emphasized Material 3 styles in the bundled Geist families and MUST respect font scale. Shapes MUST define the Material 3 corner scale including the increased tokens.

#### Scenario: Large font

- **WHEN** font scale is 2.0
- **THEN** every style scales in sp and no primary action clips

### Requirement: REQ-UIX-M3F-ICONS — Material Symbols

All app icons MUST come from Material Symbols Outlined through `RipDpiIcons`, with unchanged meanings and content descriptions. The app MUST NOT depend on `material-icons-core` or `material-icons-extended`.

#### Scenario: Resolve dependencies

- **WHEN** the app dependency graph is resolved
- **THEN** no material-icons artifact is present and every icon still renders

### Requirement: REQ-UIX-M3F-CONTRACT — Design contract alignment

`DESIGN.md`, `docs/design-system.md`, and the `material-3` and `android-compose-patterns` skills MUST describe the seed scheme, contrast behavior, opt-in dynamic color, and motion scheme. The Glance widget theme MUST use the same color source.

#### Scenario: Read the contract

- **WHEN** an agent reads the contract before a UI change
- **THEN** it finds no rule that forbids dynamic color or contradicts the implementation
