## Purpose

Specify which observations the TLS diagnostic paths record and how reports assign them to a connection.

## ADDED Requirements

### Requirement: REQ-DIA02-F1 — Preserve captured ClientHello on failure

The probe MUST preserve the JA3 of a complete first ClientHello written to the local stream, including when the handshake fails. A missing fingerprint MUST retain an explicit reason.

#### Scenario: Peer rejects the recorded ClientHello

- **WHEN** a loopback peer receives a full ClientHello and returns an alert
- **THEN** the result includes the JA3 of those bytes and the failed handshake outcome

### Requirement: REQ-DIA02-F2 — Keep evidence on its own attempt

The report MUST bind profile, peer and peer role, timing, negotiated version, certificate evidence, and fingerprint to the connection that produced them. A fresh ECH retry is a separate attempt.

#### Scenario: Different peers return different outcomes

- **WHEN** TLS 1.3 fails at one peer and TLS 1.2 succeeds at another peer
- **THEN** each result carries its own endpoint and measurements, and any successful summary takes its measurements from one identified attempt

### Requirement: REQ-DIA02-F3 — Distinguish response and cause

The probe MUST distinguish incoming alerts, actual ServerHello receipt, local certificate policy errors, and transport outcomes. An unauthenticated alert MUST leave sender and path cause unknown. Timing-based labels MUST require timing evidence.

#### Scenario: Client certificate is required

- **WHEN** the probe receives the peer alert for a missing client certificate
- **THEN** the report records that alert and does not claim a failed local server certificate check

### Requirement: REQ-DIA02-F4 — Bind ECH selection and retry

The ECH probe MUST report configuration provenance and distinguish acceptance, rejection, and an attempt with no confirmed outcome. A bundled CDN fallback MUST use a known connected endpoint. Authenticated retry MUST stay on the known endpoint and occurs at most once.

#### Scenario: The first address did not connect

- **WHEN** an ECH lookup fails and the probe connects to a different known candidate
- **THEN** the fallback uses the connected address and identifies its source

### Requirement: REQ-DIA02-F5 — Preserve partial and ECH-only outcomes

The diagnostic lanes MUST stop starting TLS attempts after cancellation and preserve completed evidence. Reports MUST distinguish cancelled, skipped, and failed attempts. ECH-only success MUST describe the measured result without asserting SNI interference. ECH certificate failure MUST contribute to aggregate diagnosis.

#### Scenario: Cancellation between profiles

- **WHEN** a TLS attempt completes and the scan is cancelled before starting the next profile
- **THEN** the report retains the completed attempt and identifies the remaining attempt as skipped or cancelled

### Requirement: REQ-DIA02-F6 — Bound and normalize JA3 input

The parser MUST reassemble the first ClientHello across TLS records and respect each declared record, handshake, and vector length. It MUST reject incomplete or malformed input. For valid input it MUST preserve JA3 field order and remove the RFC 8701 GREASE values from the affected 16-bit lists.

#### Scenario: A ClientHello spans two TLS records

- **WHEN** one complete ClientHello is sent as fragments in successive handshake records
- **THEN** the normalized JA3 equals the independently computed single-record result

### Requirement: REQ-DIA02-F7 — Report negotiated ALPN and TLS measurements

Strategy and web diagnostics MUST make per-attempt negotiated ALPN, JA3, TLS timing, and available certificate evidence accessible through the report and export. If no ALPN is negotiated, the report MUST omit the value. An HTTP/1.1 caller MUST retain its application protocol policy.

#### Scenario: The peer selects HTTP/1.1

- **WHEN** a web probe negotiates HTTP/1.1
- **THEN** the report identifies HTTP/1.1 as that attempt's negotiated ALPN

### Requirement: REQ-DIA02-F8 — Identify the certificate trust policy

The probe MUST distinguish successful default trust validation from custom policy and skipped verification. A handshake under a verifier that skips authentication MUST NOT authorize ECH retry.

#### Scenario: Custom verifier accepts an untrusted certificate

- **WHEN** a custom override permits a handshake that standard trust rejects
- **THEN** the observation identifies the override policy and makes no standard trust claim
