## Purpose

Keep imported proxy credentials, connection settings and saved profiles correct across parsing, persistence, activation and export.

## ADDED Requirements

### Requirement: REQ-IMP01-F1 — Preserve security query fields

The importer MUST decode query names once or reject their encoded form before creating a profile. It MUST reject conflicting scalar duplicates and MUST NOT discard a certificate pin, unsupported ECH, or plugin requirement.

#### Scenario: Preserve security query fields

- **WHEN** a supported URI contains canonical or percent-encoded security parameter names
- **THEN** the same pin is retained, and unsupported ECH or plugin variants fail before persistence

### Requirement: REQ-IMP01-F2 — Retain atomic imported profiles

The app MUST store the imported profile, encrypted secrets, group membership and name in one Room transaction. Confirmation MUST retain the current runtime selection and settings. Later imports MUST retain earlier profiles for separate explicit selection and export.

#### Scenario: Retain atomic imported profiles

- **WHEN** two distinct valid links are confirmed and the database is reopened
- **THEN** both imported profiles and their secrets remain available; a failed or cancelled write leaves the prior full state

### Requirement: REQ-IMP01-F3 — Retain saved Mieru options

The saved-profile exporter MUST preserve Mieru protocol, multiplexing and MTU.

#### Scenario: Retain saved Mieru options

- **WHEN** a saved Mieru profile has high multiplexing and MTU 1380
- **THEN** export and reimport retain both values

### Requirement: REQ-IMP01-F4 — Export normative SS2022 userinfo

The SS exporter MUST use percent-encoded plain method and password for SIP022, while retaining allowed Base64 userinfo for older AEAD methods.

#### Scenario: Export normative SS2022 userinfo

- **WHEN** a supported SS2022 profile is exported
- **THEN** userinfo uses the SIP002 plain form and an independent oracle sees the same key

### Requirement: REQ-IMP01-F5 — Preserve SSH userinfo bytes

The SSH URI parser MUST retain literal plus characters and decode percent escapes once.

#### Scenario: Preserve SSH userinfo bytes

- **WHEN** username or password contains a literal plus, percent-encoded plus, space or Unicode
- **THEN** the saved credentials match the intended values without form decoding

### Requirement: REQ-IMP01-F6 — Hide invalid payload credentials

The scanner MUST NOT copy any part of an invalid payload into an error preview or error model.

#### Scenario: Hide invalid payload credentials

- **WHEN** a malformed secret-bearing URI fails import
- **THEN** the error contains only static text without userinfo or query values

### Requirement: REQ-IMP01-F7 — Accept scheme case variants

The codec MUST treat supported URI schemes as case-insensitive.

#### Scenario: Accept scheme case variants

- **WHEN** an SS URI uses uppercase or mixed-case scheme text
- **THEN** it has the same parsed settings as the lowercase URI

### Requirement: REQ-IMP01-TUIC — Activate typed TUIC URI profiles

The app MUST parse, validate, persist, select and export typed TUIC v5 profiles for the existing native capability set. It MUST preserve UUID, password, endpoint, SNI, ALPN and congestion control. It MUST reject unsupported insecure, disabled-SNI, UDP-stream and zero-RTT options before writes and refuse lossy export of custom trust roots.

#### Scenario: Activate typed TUIC URI profiles

- **WHEN** a supported TUIC URI is imported, reopened, selected and shared
- **THEN** its settings and secrets reach native configuration unchanged and reimport retains them

### Requirement: REQ-IMP01-MIERU — Apply advanced Mieru TCP settings

The app MUST preserve optional protobuf field presence for pinned Mieru v3.36.0 handshake-mode and traffic-pattern settings. The native TCP engine MUST apply no-wait handshake, nonce shaping, padding bounds, fragmentation and low-entropy modes. Invalid fields MUST fail before writes. Explicit seeded behavior MUST match the pinned Go implementation. Absent whole patterns MUST preserve existing behavior; implicit defaults MUST use a non-identifying stable app label.

#### Scenario: Apply advanced Mieru TCP settings

- **WHEN** an advanced simple or standard Mieru URI is imported, activated and shared
- **THEN** typed settings retain presence, native bytes match pinned vectors and a local upstream peer exchanges payload; refusal, timeout and cancellation remain bounded

### Requirement: REQ-IMP01-BACKUP — Classify added URI profile fields

FULL backup MUST retain all TUIC and advanced Mieru settings and credentials. SHARE backup MUST omit TUIC UUID/password and Mieru username/password while retaining their non-secret transport settings. New fields MUST have an explicit allowlist classification. The coverage test MUST detect a ProxyProfile subtype missing from its representative instances.

#### Scenario: Export added URI profile fields safely

- **WHEN** typed TUIC and advanced Mieru profiles enter FULL or SHARE backup
- **THEN** FULL round-trip retains their values and field presence; SHARE succeeds, preserves public settings and removes credential values
