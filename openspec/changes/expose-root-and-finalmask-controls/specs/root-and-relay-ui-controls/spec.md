## Purpose

Keep advanced root strategies reachable for explicit opt-in and prevent the relay editor from offering Finalmask choices that cannot be saved.

## ADDED Requirements

### Requirement: REQ-ROOT-MODE-OPT-IN — Root mode remains reachable

The app MUST provide a Settings path to root strategies while root mode is disabled and MUST persist the user's explicit root mode selection.

#### Scenario: Enable root mode

- **WHEN** a user opens Settings with root mode disabled and enables it on the root strategies screen
- **THEN** the choice persists and root strategies become available

#### Scenario: Disable root mode

- **WHEN** a user turns root mode off
- **THEN** the choice persists and root strategies remain reachable but inactive

#### Scenario: Device without root

- **WHEN** root mode is enabled on a device without root access
- **THEN** the screen explains that root strategies remain inactive and the app's non-root functions remain available

### Requirement: REQ-FINALMASK-VALIDITY — Finalmask choices match save validation

The app MUST present Finalmask controls only when the selected relay kind and transport support saving Finalmask.

#### Scenario: Supported relay configuration

- **WHEN** the user edits VLESS or VLESS Reality over xHTTP, or Cloudflare Tunnel
- **THEN** Finalmask choices are available

#### Scenario: Unsupported relay configuration

- **WHEN** the user edits another relay kind or VLESS over a different transport
- **THEN** Finalmask choices are absent

#### Scenario: Existing unsupported Finalmask choice

- **WHEN** an existing choice is incompatible with the selected relay transport
- **THEN** the editor offers Off so the user can clear the choice before saving
