## Purpose

Keep AmneziaWG share links safe and readable across the local import boundaries.

## ADDED Requirements

### Requirement: REQ-AWG-URI-CONVERSION — Safe conf conversion

The shared import classifier MUST return a normal failure when a decoded URI contains a value that cannot be written safely to a conf file. It MUST retain the conf injection guard.

#### Scenario: Unsafe conf value

- **WHEN** a known URI string field contains a line break or comment character after decoding
- **THEN** classification returns no import payload and does not throw

### Requirement: REQ-AWG-URI-ROUTES — Preserve route intent

The URI decoder MUST reject an explicitly empty allowed_ips list. An omitted list MUST retain the existing configured-family defaults.

#### Scenario: Empty route policy

- **WHEN** allowed_ips is present but has no nonempty list element
- **THEN** decoding fails before an activation request can select default routes

### Requirement: REQ-AWG-URI-ENCODE — Readable semantic export

The encoder MUST omit empty I slots so a supported profile does not create an unreadable URI. External empty I query fields MUST remain invalid. The contract MUST state normalization of empty slots, lists, names, and equal keepalive bounds.

#### Scenario: Empty optional signature slot

- **WHEN** a supported profile has an empty optional I slot
- **THEN** encoding and decoding preserve its meaning without an empty I query field

### Requirement: REQ-AWG-URI-PRIVACY — Private error labels

An invalid QR result MUST NOT contain input credential bytes. It MAY contain an allowlisted format label or a fixed safe marker.

#### Scenario: Invalid credential link

- **WHEN** a credential-bearing URI is rejected
- **THEN** the error preview and its string representation contain no userinfo bytes

### Requirement: REQ-AWG-URI-DOCS — Current S3 and S4 contract

The URI document and form documentation MUST describe S3 and S4 as supported in 0 through 1280 and the nonzero header-protection prefix constraint.

#### Scenario: Header protection profile

- **WHEN** a reader configures a nonzero header-protection key
- **THEN** the documentation requires all four S fields to be at least 12 and does not impose a zero-only rule
