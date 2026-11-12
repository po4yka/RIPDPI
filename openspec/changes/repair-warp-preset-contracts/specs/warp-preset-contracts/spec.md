## Purpose

Keep WARP preset packets, saved enable state, and visible suggestions consistent
without changing the generic AmneziaWG peer contract.

## ADDED Requirements

### Requirement: REQ-WARP-PRESET-WIRE — Preserve the WireGuard packet envelope

Built-in WARP presets MUST send real WireGuard packets with standard message
headers and lengths. They MAY send bounded J/I prelude packets. The WARP
reserved-byte convention MUST remain the same as the disabled-shaping path.
Generic AWG custom envelope and cookie behavior MUST remain unchanged.

#### Scenario: Every built-in preset reaches a plain peer

- **WHEN** a built-in preset starts against a loopback WireGuard peer
- **THEN** the real initiation has 148 bytes, the response authenticates, and the
  runtime becomes ready after the peer proof rather than after the listener bind
- **AND** the configured prelude precedes each initiation

#### Scenario: A WARP client has nonzero reserved bytes

- **WHEN** the same Noise message is sent with shaping off or a prelude-only preset
- **THEN** the real packet and its MAC are equal after the reserved-byte overlay
- **AND** standard peer responses and cookie challenges remain valid

### Requirement: REQ-WARP-PRESET-ENABLED — Use one effective enable state

An explicit disabled flag MUST disable shaping for every preset. Off and random
MUST remain disabled. Explicit UI selection of an imitation or custom preset
MUST enable it. JSON export and restore MUST report the effective enable state.
The supported wire-profile admission checks MUST remain active.

#### Scenario: An imported named preset is disabled

- **WHEN** a valid imported profile names an imitation preset with enabled false
- **THEN** settings projection, backup round trip, and native mapping keep it false

#### Scenario: A user selects a preset

- **WHEN** the user selects off, random, an imitation preset, or custom
- **THEN** stored and projected enable flags agree with the selected mode
- **AND** named presets replace their owned J/H/S/I fields while custom keeps its
  supported exact values

### Requirement: REQ-WARP-PRESET-HINT — Distinguish suggestions from applied state

The settings screen MUST label an unselected preset as a suggestion. Network
hints MUST NOT apply presets or expose raw operator identifiers in UI messages.

#### Scenario: A different preset is suggested

- **WHEN** WARP is enabled and the network suggests a preset other than the selection
- **THEN** the suggestion has a recommendation title and the selected preset does
  not change

### Requirement: REQ-WARP-PRESET-HINT-REFRESH — Refresh hints with network changes

The settings screen MUST refresh the suggestion when the observed physical
network changes. A network refresh MUST NOT require a settings mutation or
reload unrelated routing catalogs on each telemetry event.

#### Scenario: The network hint changes while settings remain unchanged

- **WHEN** the observed network changes from Russian cellular to another 5G
  network and then to Wi-Fi
- **THEN** the suggestion changes from QUIC to TLS and then disappears
- **AND** the selected preset and saved settings remain unchanged
