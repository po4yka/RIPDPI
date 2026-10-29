## Purpose

Preserve native VLESS REALITY profile intent and maintain safe, compatible stream transitions against the reviewed upstream protocol.

## ADDED Requirements

### Requirement: REQ-IMPORT-INTENT — Reject unsupported settings

Native URI and JSON import MUST reject significant settings that the native profile cannot preserve, including ML-DSA verification, VLESS Encryption, and unsupported XHTTP options. Rejection MUST NOT expose credential values.

#### Scenario: Requested authentication is unsupported

- **WHEN** a valid REALITY profile requests mldsa65Verify or non-none VLESS Encryption
- **THEN** import rejects it and produces no downgraded native profile

### Requirement: REQ-XUDP-FLUSH — Complete bounded writes

XUDP MUST complete both writing and flushing a frame within one deadline before confirming send success. A timeout MUST terminate that carrier.

#### Scenario: Vision buffers a frame

- **WHEN** a Vision-wrapped XUDP carrier cannot accept the complete padded frame
- **THEN** send remains pending until the frame is flushed or the deadline fails

### Requirement: REQ-TRANSPORT-ALIAS — Honor transport selection

Native JSON import MUST resolve method before network and reject unsupported selected transports rather than substitute TCP.

#### Scenario: Modern alias overrides a legacy value

- **WHEN** method selects XHTTP and network selects TCP
- **THEN** the imported profile selects XHTTP and retains its supported settings

### Requirement: REQ-XHTTP-AUTO — Resolve automatic mode in context

Native XHTTP MUST resolve automatic mode according to the reviewed upstream security context. A resolution to an unsupported mode MUST fail explicitly.

#### Scenario: REALITY automatic mode

- **WHEN** a REALITY XHTTP profile selects auto without a separate download path
- **THEN** the client uses stream-one

### Requirement: REQ-VISION-BOUNDARY — Preserve complete TLS records

Vision MUST switch to Direct only after a complete inner TLS record boundary. Fragmented headers, payloads, and coalesced records MUST preserve the inner stream and outer record boundary.

#### Scenario: Application record payload spans writes

- **WHEN** an eligible inner TLS application record arrives in multiple writes
- **THEN** Direct does not start before its remaining payload completes

### Requirement: REQ-REALITY-VERSION — Send the reviewed compatibility version

The REALITY admission payload MUST use an explicit documented version tied to the reviewed handshake reference rather than the placeholder 0.0.1.

#### Scenario: Server enforces a compatible minimum

- **WHEN** the admission payload is decoded under the server key and checked against a compatible minimum and maximum
- **THEN** its version is admitted, while a minimum above the documented version rejects it
