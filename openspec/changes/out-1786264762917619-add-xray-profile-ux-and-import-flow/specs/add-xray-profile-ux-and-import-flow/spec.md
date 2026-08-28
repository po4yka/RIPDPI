## Purpose

Define the observable completion contract for Add Xray profile UX and import flow. Add the user-facing flow for selecting Xray VPN mode and importing or editing initial Xray profiles.

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917619-001 — Mode Editor can select Xray-backed VPN mode separately from native RIPDPI direct/proxy modes

The Mode Editor MUST present Xray-backed VPN as a selectable service-mode option that is distinct from native direct and native proxy modes.

#### Scenario: Select Xray VPN mode

- **WHEN** the user confirms the Xray VPN selection with an accepted profile in the Mode Editor
- **THEN** the selection MUST persist the Xray provider kind and VPN mode without activating a native relay profile
- **AND** confirming a native option MUST persist the native provider kind for the selected native mode

### Requirement: REQ-OUT-1786264762917619-002 — Import supports at least the first approved share/config shapes and fails closed on unsupported or unsafe fields

The import flow MUST accept the approved Xray profile inputs and reject unsupported or unsafe inputs without producing a runnable partial profile.

#### Scenario: Import accepted profile

- **WHEN** the user imports a supported VLESS share link or JSON representation of the approved client shape
- **THEN** the flow MUST parse it into a typed Xray profile, validate the rendered configuration, and make it available for Xray provider selection without silently dropping supplied fields or changing their meaning; unsupported fields MUST cause rejection

#### Scenario: Reject unsupported profile

- **WHEN** the user imports an unsupported scheme, unsupported transport, missing required field, malformed JSON, or unsafe option
- **THEN** the flow MUST reject the import and leave the previously persisted provider selection and profile unchanged

### Requirement: REQ-OUT-1786264762917619-003 — Validation errors are actionable but redact credentials and endpoints

Validation failures MUST return user-actionable error information without exposing profile credentials, private keys, UUIDs, server addresses, SNI values, or live endpoints.

#### Scenario: Render safe validation message

- **WHEN** profile import or validation fails for an input that contains secrets or endpoints
- **THEN** the UI-visible error MUST describe the missing or unsupported capability class
- **AND** the error text MUST not contain raw credential or endpoint values from the rejected input

### Requirement: REQ-OUT-1786264762917619-004 — Onboarding can validate an Xray profile as the chosen mode before finish

Onboarding MUST provide access to Xray profile import and validation before completion.

#### Scenario: Validate a profile before completing Xray import

- **WHEN** the user opens Xray import from onboarding with Xray VPN selected and no accepted profile is available
- **THEN** completion of the Xray import flow MUST remain blocked
- **AND** after the user confirms a supported profile for Xray VPN, completing onboarding in VPN mode MUST preserve Xray with that profile as the active provider selection

### Requirement: REQ-OUT-1786264762917619-005 — Compose/UI tests cover selection, validation failure, and successful imported-profile state

UI regression coverage MUST exercise the Xray import screen and view-model states that users can reach while selecting, rejecting, and accepting an Xray profile.

#### Scenario: UI state coverage

- **WHEN** the Xray profile UX tests run
- **THEN** they MUST cover native-to-Xray selection, validation failure rendering, accepted profile rendering, and persistence of a successful imported-profile state
