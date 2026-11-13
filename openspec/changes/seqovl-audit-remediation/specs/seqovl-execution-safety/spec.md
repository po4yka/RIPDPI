## Purpose

Keep TCP sequence overlap execution, validation and error outcomes consistent across local and helper paths.

## ADDED Requirements

### Requirement: REQ-SEQOVL-FLAGS — Preserve overlap with TCP flags

The implementation MUST keep seqovl execution and configured TCP flag overrides consistent without silently emitting a plain stream.

#### Scenario: Preserve overlap with TCP flags

- **WHEN** A seqovl step has a supported nonzero flag mask
- **THEN** the mask reaches the overlap sender or the input fails before emission

### Requirement: REQ-SEQOVL-RECEIPT — Report effective fallback

The implementation MUST report split and a fallback reason after a proven safe raw refusal.

#### Scenario: Report effective fallback

- **WHEN** a first-round overlap sender refuses before commit
- **THEN** the peer receives genuine data once and the receipt identifies split

### Requirement: REQ-SEQOVL-CHAIN — Reject terminal chain misuse

All configuration inputs MUST reject any TCP send step after seqovl. TLS prelude steps MAY precede seqovl.

#### Scenario: Reject terminal chain misuse

- **WHEN** a send step follows seqovl in CLI, JSON or Kotlin input
- **THEN** validation fails before a runtime plan is sent

### Requirement: REQ-SEQOVL-RESTORE — Preserve commit uncertainty

The raw sender MUST permit replay only after proven no-emission and successful repair restoration.

#### Scenario: Preserve commit uncertainty

- **WHEN** a raw attempt or repair queue commit may have occurred, or repair restoration fails
- **THEN** the operation returns a terminal error and no plain replay is permitted

### Requirement: REQ-SEQOVL-MTU — Handle raw packet size safely

The sender MUST include prefix and headers in known packet budgets and permit safe fallback on a proven pre-emission size refusal.

#### Scenario: Handle raw packet size safely

- **WHEN** a packet exceeds the known size budget or an atomic send reports EMSGSIZE
- **THEN** no partial genuine replay occurs and a safe refusal can become split

### Requirement: REQ-SEQOVL-HELPER — Preserve safe helper refusals

Helper error metadata MUST preserve only explicit safe seqovl refusal kinds. Missing, invalid and unknown-outcome metadata MUST remain terminal.

#### Scenario: Preserve safe helper refusals

- **WHEN** the helper returns a restored pre-emission refusal or loses its response after delivery
- **THEN** only the proven refusal permits split; response loss never triggers replay

### Requirement: REQ-SEQOVL-CAPABILITY — Probe repair and raw access

Capability reporting MUST require TCP repair and at least one available raw IP family.

#### Scenario: Probe repair and raw access

- **WHEN** repair is available but both raw families are unavailable
- **THEN** seqovl support is false

### Requirement: REQ-SEQOVL-BOUNDARY — Preserve privacy and activation

The change MUST preserve root opt-in, VPN socket protection, the first-round activation gate, and payload redaction. It MUST NOT claim external DPI compatibility from unit tests.

#### Scenario: Local verification

- **WHEN** regression tests run without privileged or external probes
- **THEN** evidence remains local and root or external compatibility is reported as unverified
