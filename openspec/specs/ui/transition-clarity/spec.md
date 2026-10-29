# ui/transition-clarity Specification

## Purpose
Keep connection and History transitions understandable and show safe saved intent before a confirmed reconnect.

## Requirements

### Requirement: REQ-UTC-RTL — History reading direction

History section transitions MUST mirror physical movement in RTL for forward and reverse selection. They MUST preserve section state and existing static and reduced-motion behavior.

#### Scenario: RTL forward navigation

- **WHEN** a user moves to a later History section in RTL
- **THEN** incoming and outgoing movement is the mirror of the same LTR transition, without clearing filters or reader state.

### Requirement: REQ-UTC-FEEDBACK — Honest connection feedback

The connection indicator MUST use a stable slot and a short token-based transition keyed only by semantic status. It MUST leave Cancel available, preserve current accessible status, and snap with animations disabled.

#### Scenario: Connection completes

- **WHEN** an engaging connection reports a connected or fault status
- **THEN** only the indicator transitions, the current action remains operable, and repeated telemetry does not replay the transition.

#### Scenario: Reduced or disabled motion

- **WHEN** reduced motion or disabled animation is selected
- **THEN** feedback uses a reduced fade or an immediate final indicator respectively, with no invented progress or delayed action.

### Requirement: REQ-UTC-PREVIEW — Safe reconnect comparison

Reconnect confirmation MUST compare whitelisted applied facets with saved intent and explain unknown or unlisted changes. It MUST not promise effective selection from saved intent, expose secrets, endpoint hosts, raw CLI, profile names, or configuration hashes.

#### Scenario: Saved settings differ

- **WHEN** pending saved settings are reviewed before reconnect
- **THEN** the dialog shows readable applied and saved values for safe comparable facets and retains explicit confirm and cancel actions.

#### Scenario: Unknown or custom settings

- **WHEN** an applied value is unknown or saved custom configuration cannot be safely summarized
- **THEN** the comparison labels that limit without inventing a value or exposing the custom configuration.

### Requirement: REQ-UTC-SNAPSHOT — Current confirmation only

A changed runtime or saved-settings snapshot MUST invalidate an open comparison. Cancellation and stale confirmation MUST not request reconnect. Existing service admission and runtime confirmation checks MUST remain authoritative.

#### Scenario: Settings change during review

- **WHEN** any saved setting changes while the comparison is open
- **THEN** the old comparison closes or becomes ineligible and cannot confirm the changed settings without a fresh review.

#### Scenario: Runtime changes or review is cancelled

- **WHEN** the runtime revision changes or the user cancels review
- **THEN** no reconnect callback is dispatched from that old review.

### Requirement: REQ-UTC-VALIDATION — Observable acceptance

The change MUST pass focused regressions, locale and theme checks, static analysis, independent review, and a real-APK emulator check before authorized main publication. Local, device, artifact and hosted CI proof MUST remain separate.

#### Scenario: Publication

- **WHEN** the user-authorized change is integrated and pushed
- **THEN** the exact published revision and its hosted CI result are recorded separately from local and emulator checks.
