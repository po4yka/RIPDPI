## Purpose

Keep existing WebSocket and raw HTTP Upgrade transports correct at validation, error, privacy, and termination boundaries.

## ADDED Requirements

### Requirement: REQ-WS-EXTENSIONS — Reject unrequested extensions

The client MUST reject an extension not offered in its request before it returns a usable carrier.

#### Scenario: Reject unrequested extensions

- **WHEN** the server adds an unrequested extension to an otherwise valid 101 response
- **THEN** the handshake fails before tunnel payload is sent

### Requirement: REQ-WS-CONNECTION — Read Connection tokens

The client MUST accept an Upgrade token in a valid Connection field list, including repeated fields.

#### Scenario: Read Connection tokens

- **WHEN** a valid response includes keep-alive and Upgrade tokens
- **THEN** the handshake succeeds without weakening Accept or subprotocol checks

### Requirement: REQ-WS-ERRORS — Preserve terminal relay errors

The relay MUST return terminal protocol, capacity, and I/O errors to its caller and MUST retain normal Close behavior.

#### Scenario: Preserve terminal relay errors

- **WHEN** the peer sends an invalid frame or a terminal send fails
- **THEN** the result is an error and owned workers terminate

### Requirement: REQ-UPGRADE-RESPONSE — Validate raw Upgrade responses

The raw transport MUST validate HTTP syntax, status, Connection, and the offered Upgrade protocol within a bounded header budget. It MUST retain post-head bytes and support bounded interim responses.

#### Scenario: Validate raw Upgrade responses

- **WHEN** an invalid 101 response or valid interim response sequence arrives
- **THEN** the invalid response fails and the valid sequence preserves its payload

### Requirement: REQ-WS-CLOSE — Send a peer Close reply

The existing async WS adapters MUST send the queued peer Close reply before they return terminal EOF or Closed. They MUST bound a stalled close write.

#### Scenario: Send a peer Close reply

- **WHEN** a peer sends Close while no application write is pending
- **THEN** the peer receives a masked Close reply and the adapter terminates within its deadline

### Requirement: REQ-UPGRADE-REQUEST — Validate raw Upgrade requests

The builder MUST reject invalid authority, origin-form, field names, and protocol tokens before writing request bytes.

#### Scenario: Validate raw Upgrade requests

- **WHEN** the configuration contains an empty host or invalid request syntax
- **THEN** the operation returns InvalidConfig without a partial request

### Requirement: REQ-WS-PRIVACY-LIMITS — Keep bounded and private transports

The implementation MUST keep TLS identity checks, prevent credential output in handshake logs, and bound WireGuard WS messages to its datagram contract.

#### Scenario: Keep bounded and private transports

- **WHEN** trace logging is enabled or a carrier exceeds the supported datagram size
- **THEN** credentials remain absent and oversize input is rejected before delivery
