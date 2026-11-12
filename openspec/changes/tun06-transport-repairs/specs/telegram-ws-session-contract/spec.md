## Purpose

Define the Telegram WebSocket session contract for route selection, binary delivery, cancellation, and durable credentials.

## ADDED Requirements

### Requirement: REQ-TRN-1794494451740063-F1 — Worker binary delivery

The implementation MUST preserve binary messages with the current Worker binaryType default and reject unsupported message types.

#### Scenario: Verify F1

- **WHEN** a client sends binary init and payload through the Worker
- **THEN** both directions deliver the same bytes; text is rejected.

### Requirement: REQ-TRN-1794494451740063-F2 — Direct destination DC authority

The implementation MUST use an exact known direct destination as the DC authority for structurally valid init data, without trusting random DC bytes.

#### Scenario: Verify F2

- **WHEN** valid direct init contains another production, test, or media DC value
- **THEN** the exact destination selects the carrier; unknown destinations retain the existing supported policy.

### Requirement: REQ-TRN-1794494451740063-F3 — Canonical Worker upstream paths

The implementation MUST accept canonical production and test gateway paths and reject unrelated hosts and paths.

#### Scenario: Verify F3

- **WHEN** the client selects a supported production or test DC
- **THEN** the reference Worker accepts its canonical URL and confines the upstream.

### Requirement: REQ-TRN-1794494451740063-F4 — Partial init completion

The implementation MUST complete a preserved partial init under a bounded read budget and preserve all bytes.

#### Scenario: Verify F4

- **WHEN** a direct write fails after an initial client fragment and further init bytes remain available
- **THEN** WS fallback completes the init; EOF and timeout return the exact collected bytes.

### Requirement: REQ-TRN-1794494451740063-F5 — Bootstrap cancellation

The implementation MUST apply runtime stop and one bounded bootstrap budget to DNS and carrier setup for every ingress and reopen path.

#### Scenario: Verify F5

- **WHEN** stop occurs during DNS, TLS, or Upgrade, including a changed DC
- **THEN** the pending bootstrap exits and releases its carrier without waiting for the configured connect timeout.

### Requirement: REQ-TRN-1794494451740063-F6 — Durable credential publication

The implementation MUST preserve a coherent active settings and credential pair through cancellation, publication failure, and process restart.

#### Scenario: Verify F6

- **WHEN** credential replacement or removal crosses a durable stage
- **THEN** restart resolves either the old or new complete configuration; unpublished encrypted records may remain but must not replace the active credential.

### Requirement: REQ-TRN-1794494451740063-F7 — Init prefix validity

The implementation MUST reject an obfuscated2 init with a zero second word before classifying it as valid MTProto.

#### Scenario: Verify F7

- **WHEN** an init has a valid tag but an invalid second word
- **THEN** classification rejects it while supported valid init vectors remain accepted.

### Requirement: REQ-TRN-1794494451740063-F8 — Worker close propagation

The implementation MUST preserve a close without a status and MUST NOT pass reserved observation codes to the WebSocket close API.

#### Scenario: Verify F8

- **WHEN** either peer closes without a status or disconnects abnormally
- **THEN** the relay propagates an empty close or a valid error close without a close API exception; ordinary status and reason remain unchanged.
