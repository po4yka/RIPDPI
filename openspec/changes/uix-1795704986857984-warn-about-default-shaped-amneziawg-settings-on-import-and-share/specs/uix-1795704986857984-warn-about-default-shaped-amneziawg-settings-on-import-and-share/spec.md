## Purpose

Define the observable completion contract for Warn about default-shaped AmneziaWG settings on import and share. Importing or sharing an AmneziaWG config warns the user when its handshake looks like plain WireGuard, when its header values are defaults, or when its I1 packet equals a publicly known default.

## ADDED Requirements

### Requirement: REQ-UIX-1795704986857984-001 — Import warns when the parsed handshake shape is identical to plain WireGuard be...

The RIPDPI implementation MUST satisfy this portfolio criterion: Import warns when the parsed handshake shape is identical to plain WireGuard because no obfuscation field is set.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that import warns when the parsed handshake shape is identical to plain WireGuard because no obfuscation field is set

### Requirement: REQ-UIX-1795704986857984-002 — Import and share warn when the header values equal the documented defaults.

The RIPDPI implementation MUST satisfy this portfolio criterion: Import and share warn when the header values equal the documented defaults.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that import and share warn when the header values equal the documented defaults

### Requirement: REQ-UIX-1795704986857984-003 — Import and share warn when the I1 packet matches the digest of a publicly known...

The RIPDPI implementation MUST satisfy this portfolio criterion: Import and share warn when the I1 packet matches the digest of a publicly known default packet.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that import and share warn when the I1 packet matches the digest of a publicly known default packet

### Requirement: REQ-UIX-1795704986857984-004 — Warnings are advisory: the user can continue, and the warning text and logs hol...

The RIPDPI implementation MUST satisfy this portfolio criterion: Warnings are advisory: the user can continue, and the warning text and logs hold no packet bytes or key material.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that warnings are advisory: the user can continue, and the warning text and logs hold no packet bytes or key material
