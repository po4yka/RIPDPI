## Purpose

Define bounded, ordered, privacy-safe evidence for the protocol stages reached
by one VLESS Reality relay attempt so failures can be localized without packet
payload capture or causal overstatement.

## ADDED Requirements

### Requirement: REQ-DGN-1786592449526581-001 — Attempts expose ordered protocol stages

The implementation MUST assign each VLESS Reality TCP relay attempt an opaque
attempt identifier and MUST emit monotonically ordered stage records for TCP
connect, Reality TLS, VLESS request write, first VLESS response validation,
SOCKS result, and terminal close or failure whenever those stages are reached.

#### Scenario: A relay attempt reaches its first upstream response

- **WHEN** a VLESS Reality TCP attempt completes TCP and Reality TLS, writes the VLESS request, and receives a valid first response
- **THEN** its trace contains those completed stages in causal order under one opaque attempt identifier

### Requirement: REQ-DGN-1786592449526581-002 — Failed attempts retain partial typed evidence

The implementation MUST retain stages completed before failure and MUST record
the failing stage with a typed outcome, duration, failure stage or class, I/O
error kind, errno, and peer-close phase only where the runtime observed that
evidence.

#### Scenario: The peer resets during Reality TLS

- **WHEN** TCP connect succeeds and the peer resets the connection before Reality TLS completes
- **THEN** the trace retains the successful TCP stage, records a failed Reality TLS stage, and does not fabricate later VLESS or SOCKS milestones

### Requirement: REQ-DGN-1786592449526581-003 — Correlation survives export

The implementation MUST carry relay attempt stage records through the additive
native runtime telemetry boundary, associate them with the owning runtime and
connection session, persist them, and export them as structured diagnostic
archive evidence.

#### Scenario: A failed attempt is exported after the runtime stops

- **WHEN** an owning connection session is finalized after a failed relay attempt
- **THEN** the diagnostic archive still contains the ordered partial trace and its opaque runtime, session, and attempt correlations

### Requirement: REQ-DGN-1786592449526581-004 — Collection remains bounded and privacy-safe

The implementation MUST collect attempt stages through a bounded non-blocking
control-plane surface and MUST NOT emit per-packet or per-byte events, UUIDs,
credentials, raw endpoints, device or network identifiers, ClientHello bytes,
request or response payloads, or packet payloads.

#### Scenario: Repeated attempts exceed retained capacity

- **WHEN** relay attempt stage events exceed the configured bounded capacity
- **THEN** collection remains non-blocking, retention follows the documented bounded policy, and no forbidden value appears in telemetry or archive output

### Requirement: REQ-DGN-1786592449526581-005 — Additive telemetry remains forward tolerant

The implementation MUST make every new native telemetry field optional and
defaulted so absent fields from an older producer decode successfully and
unknown fields from a newer producer remain tolerated.

#### Scenario: A snapshot has no attempt-stage fields

- **WHEN** the Kotlin consumer decodes an otherwise valid current-schema native runtime snapshot produced without attempt-stage fields
- **THEN** decoding succeeds and exposes an empty attempt-stage trace without changing existing runtime behavior

### Requirement: REQ-DGN-1786592449526581-006 — Existing relay telemetry survives diagnostic export

The implementation MUST persist already-collected relay native events in the
same live and terminal connection-session paths as proxy and tunnel events and
MUST include them in the existing redacted native-event archive export without
requiring a native, Room, or archive schema change.

#### Scenario: A relay runtime stops before terminal persistence

- **WHEN** the current relay telemetry snapshot contains a `runtime_stopped` native event
- **THEN** the event remains associated with the owning connection session and is available to the redacted `native-events.csv` diagnostic export after the session stops

### Requirement: REQ-DGN-1786592449526581-007 — Effective configuration has a privacy-safe fingerprint

The implementation MUST export a deterministic, versioned SHA-256 fingerprint
of the effective allowlisted strategy projection and MUST NOT export the
projection itself, raw runtime configuration, endpoints, network identifiers,
or credentials as fingerprint material or adjacent evidence.

#### Scenario: Two archives used the same effective strategy

- **WHEN** two diagnostic archives contain the same effective allowlisted strategy projection
- **THEN** `runtime-config.json` contains the same effective-configuration fingerprint in both archives and contains no raw signature fields or sensitive source values
