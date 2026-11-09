# tls-kem-audit-contracts Specification

## Purpose
Keep hybrid TLS peer failures, profile metadata, and group documentation consistent with their observable contracts.

## Requirements

### Requirement: REQ-TRN-1794199027304873-001 — Preserve key-exchange alerts

The TLS client MUST preserve the failure alert selected by its key-exchange implementation. An invalid X25519MLKEM768 share length or invalid X25519 component MUST close the handshake with illegal_parameter. A failure that does not select an alert MUST retain the internal_error default. Successful hybrid exchange, classical fallback, and certificate verification MUST remain intact.

#### Scenario: Invalid hybrid peer share

- **WHEN** a TLS 1.3 peer sends a correctly framed hybrid share with length 1119 or 1121, or a 1120-byte share with a zero X25519 component
- **THEN** the client closes without application data and sends fatal illegal_parameter on the wire

#### Scenario: Framing and group controls

- **WHEN** the peer selects an unoffered group or sends an invalid key_share vector length
- **THEN** the client sends the corresponding protocol error and does not return a connected stream

### Requirement: REQ-TRN-1794199027304873-002 — Report only validated profile properties

Profile metadata MUST describe configuration validation without claiming that a ClientHello size was measured. A reference size hint MUST NOT be presented as an actual wire size or a hybrid-flight bound. This correction MUST NOT alter emitted TLS bytes, group preferences, certificate policy, or packet fixtures.

#### Scenario: A valid 517-byte first record

- **WHEN** a valid Firefox or Safari profile sends a 517-byte first record for template-validation.test
- **THEN** its metadata reports satisfied configuration invariants and does not claim avoidance of that wire size

### Requirement: REQ-TRN-1794199027304873-003 — State REALITY group constraints

The public REALITY KEM configuration documentation MUST state that X25519MLKEM768 and X25519 form a mandatory prefix for both default and explicit group lists. The documented caller preference MUST apply only to remaining groups. Authentication and the mandatory prefix MUST remain intact.

#### Scenario: Restricted override

- **WHEN** a caller reads the API for an explicit P256 or hybrid-only group list
- **THEN** the documentation states that the mandatory pair is still added and that the option is not a strict PQ-only policy

### Requirement: REQ-TRN-1794199027304873-004 — Name legacy capture groups accurately

Fake-profile provenance MUST distinguish obsolete X25519Kyber768Draft00 at 0x6399 from X25519MLKEM768 at 0x11ec. A recorded fake share MUST NOT be described as proof of a live key exchange. The correction MUST preserve capture bytes and existing drift checks.

#### Scenario: Legacy capture provenance

- **WHEN** a reader inspects the RutrackerKyber capture description
- **THEN** the text names the legacy draft group and does not attribute standard ML-KEM support to that codepoint
