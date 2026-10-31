# ui/m3-adaptive-app-shell Specification

## Purpose
Give the app one adaptive Material 3 shell: navigation that changes form with the window size class, Material 3 top app bars, and predictive back animations.

## Requirements

### Requirement: REQ-UIX-M3S-NAV — Adaptive navigation suite

The app MUST show a navigation bar on compact windows, a navigation rail on medium and expanded windows, and a rail or drawer on large and extra-large windows. Destination order, selection state, labels, and automation IDs MUST stay the same.

#### Scenario: Adaptive navigation suite

- **WHEN** the window changes between compact, medium, and expanded widths
- **THEN** the navigation form changes and the selected destination stays selected

### Requirement: REQ-UIX-M3S-SIZE — One window size class source

Shell, scaffolds, and RipDpiLayout MUST read one window size class source that includes large and extra-large widths.

#### Scenario: One window size class source

- **WHEN** the app runs in a 1300dp wide window
- **THEN** the layout resolves the large class and content keeps its maximum width

### Requirement: REQ-UIX-M3S-APPBAR — Material 3 top app bars

Screens MUST use Material 3 top app bars with scroll behavior. Large titles MUST wrap instead of truncating at 200% font scale.

#### Scenario: Material 3 top app bars

- **WHEN** a user scrolls a long settings screen at 200% font
- **THEN** the app bar collapses and the title stays readable

### Requirement: REQ-UIX-M3S-BACK — Predictive back

Back navigation MUST show predictive back animations and MUST respect reduced motion.

#### Scenario: Predictive back

- **WHEN** a user starts a back gesture on a detail screen
- **THEN** the previous destination previews before the gesture commits
