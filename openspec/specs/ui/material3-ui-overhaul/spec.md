# ui/material3-ui-overhaul Specification

## Purpose
Define the completion contract for the Material 3 UI and UX overhaul epic.

## Requirements

### Requirement: REQ-EPC-1793256695505524-001 — Child changes complete

The epic MUST close only after the theme foundation, adaptive app shell, component facade, and screen layout child tasks are done and their OpenSpec changes are archived.

#### Scenario: Close the epic

- **WHEN** the epic is prepared for closure
- **THEN** every child task is done and its change is archived

### Requirement: REQ-EPC-1793256695505524-002 — Theme variants on adaptive windows

The app MUST render light, dark, medium contrast, high contrast, and dynamic color variants on a compact phone window and on a medium or expanded window, at 200% font scale and in a right-to-left locale.

#### Scenario: Inspect variants

- **WHEN** the installed app runs on a phone emulator and a tablet or foldable emulator with each variant
- **THEN** primary content and actions are visible, unclipped, and reachable

### Requirement: REQ-EPC-1793256695505524-003 — No deprecated icon library

The app MUST NOT depend on `material-icons-core` or `material-icons-extended`.

#### Scenario: Inspect dependencies

- **WHEN** the app dependency graph is resolved
- **THEN** neither icon artifact is present

### Requirement: REQ-EPC-1793256695505524-004 — Design contract matches implementation

`DESIGN.md`, `docs/design-system.md`, and the project UI skills MUST describe the Material 3 contract that the code implements.

#### Scenario: Read the contract

- **WHEN** an agent reads the design contract and the UI skills
- **THEN** they describe the seed scheme, opt-in dynamic color, contrast levels, motion scheme, and the component facade
