# android-permission-prompts Specification

## Purpose

Give users enough context to decide whether to allow access, and retain the
correct action when they decline or return from Android settings.

## Requirements

### Requirement: REQ-ANDROID-PERMISSION-CONTEXT — Explain requests

The app MUST explain the purpose and effect before it opens the Android UI.

#### Scenario: Start a feature that requires VPN consent

- **WHEN** a feature needs VPN consent
- **THEN** the user can read the reason, continue, or leave before Android opens the request

#### Scenario: Open installer or boot settings

- **WHEN** Android installer access or a boot setting is unavailable
- **THEN** the app explains the effect and opens Android settings on a separate action

### Requirement: REQ-ANDROID-PERMISSION-RECOVERY — Recover from denied access

The app MUST use current grants and permission results to select recovery and
keep optional features available after a refusal.

#### Scenario: Close a prompt or give a partial grant

- **WHEN** the user closes a prompt or gives a partial grant or denial
- **THEN** the app offers the applicable retry or settings action and keeps unaffected features available

#### Scenario: Return after changing access

- **WHEN** the user returns after a grant or settings change
- **THEN** the app reads current access and continues only the action that the user requested
