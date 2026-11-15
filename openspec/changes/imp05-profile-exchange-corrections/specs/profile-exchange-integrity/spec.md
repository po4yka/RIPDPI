## Purpose

The exchange workflow transfers supported relay settings and credentials between local profile storage and user-selected import or export surfaces.

## ADDED Requirements

### Requirement: REQ-PROFILE-EXCHANGE-IMPORT — Save separately and check admission

The implementation MUST preserve existing profiles and selection when it saves a new profile. It MUST reject unsupported profiles before it offers confirmation.

#### Scenario: Import two links

- **WHEN** the user confirms two supported profile imports
- **THEN** both profiles remain available under different IDs and the active selection is unchanged

### Requirement: REQ-PROFILE-EXCHANGE-INTAKE — Use bounded, consistent parsing

The implementation MUST use shared format rules across supported entry points and show a safe error for a malformed input or a conflicting chunk.

#### Scenario: Reject a broken import

- **WHEN** an import contains an invalid field or a conflicting QR chunk
- **THEN** the application rejects the input without saving or activating a profile

### Requirement: REQ-PROFILE-EXCHANGE-EXPORT — Share a faithful representation

The implementation MUST read one generation of profile data and export the supported connection settings. It MUST refuse export when the link would omit an active transport or trust setting.

#### Scenario: Export a profile

- **WHEN** the user shares a profile with representable parameters
- **THEN** the link keeps those parameters and uses the applicable URI encoding

### Requirement: REQ-PROFILE-EXCHANGE-QR — Render and report QR safely

The implementation MUST preserve valid Unicode, report rejected content without showing it, and reserve at least four white modules at the QR edges.

#### Scenario: Handle an unencodable or invalid input

- **WHEN** a profile link exceeds QR capacity or a selected image cannot be decoded
- **THEN** the interface shows the applicable error
