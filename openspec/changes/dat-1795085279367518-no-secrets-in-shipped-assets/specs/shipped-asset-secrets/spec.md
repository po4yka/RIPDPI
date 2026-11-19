## Purpose

Prevent credential material from shipping inside application assets and define how embedded relay credentials reach the device instead.

## ADDED Requirements

### Requirement: REQ-DAT-1795085279367518-001 — Build fails on secrets in assets

The implementation MUST fail the build when any file under an app asset directory contains a private key, a preshared key, or a static proxy password.

#### Scenario: Asset with a private key is rejected

- **WHEN** the guard scans a fixture asset containing a non-empty \`private_key\` value
- **THEN** it exits non-zero and names the file and key, without printing the value

#### Scenario: Clean asset passes

- **WHEN** the guard scans assets that hold only endpoint metadata and an empty token slot
- **THEN** it exits zero

### Requirement: REQ-DAT-1795085279367518-002 — Guard covers every asset directory

The implementation MUST scan all flavor and main asset directories, including a preset document, and MUST NOT rely on an allow-list of file names.

#### Scenario: Preset document is scanned

- **WHEN** a fixture \`preset.json\` under main assets contains a \`password\` field with a literal value
- **THEN** the guard fails

### Requirement: REQ-DAT-1795085279367518-003 — Relay credentials come from bootstrap provisioning

The implementation MUST obtain embedded relay credentials through the one-time \`/bootstrap/<token>\` provisioning and MUST store them in Keystore-backed storage, not in assets, preferences in plaintext, or logs.

#### Scenario: First run provisions credentials

- **WHEN** the app starts with a valid one-time token and a reachable provisioning endpoint
- **THEN** the relay profile has credentials in Keystore-backed storage and the token is consumed

#### Scenario: Provisioning fails

- **WHEN** the bootstrap request fails or the token was already used
- **THEN** no credential is stored, the profile stays unusable with a typed error, and the app does not fall back to any bundled secret

### Requirement: REQ-DAT-1795085279367518-004 — Shipped keys have a rotation note

The implementation MUST record every key and password that already shipped in an APK so operators can rotate them, without recording the secret values.

#### Scenario: Rotation note lists identifiers only

- **WHEN** the rotation note is reviewed
- **THEN** it lists key kinds, asset path, and release versions, and contains no secret value
