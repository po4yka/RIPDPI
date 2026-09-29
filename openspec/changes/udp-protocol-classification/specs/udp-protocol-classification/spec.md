## Purpose

Use the existing UDP classifier to select TUN strategies without applying protocol-scoped actions to unrelated datagrams.

## ADDED Requirements

### Requirement: REQ-UDP-001 — Match UDP payload protocols

The interceptor MUST match QUIC, DTLS, STUN, DHT, and WireGuard rules against the existing detected UDP protocol. Empty and Any filters MUST retain their catch-all behavior. TCP, port, and host filters MUST retain their current behavior.

#### Scenario: Scoped rule receives another protocol

- **WHEN** a UDP datagram has a different detected protocol, unknown bytes, or insufficient signature bytes
- **THEN** the scoped rule does not inject or consume the packet.

#### Scenario: Matching protocol on either IP family

- **WHEN** IPv4 or IPv6 carries a matching UDP payload and matching ports
- **THEN** the selected rule runs and retains the original packet endpoints.

### Requirement: REQ-UDP-002 — Provide typed TUN strategy context

The interceptor MUST use the existing classifier with actual source and destination ports for UDP strategy dissection. It MUST preserve WireGuard message type, DTLS hello details, QUIC version and markers, and port-dependent DNS classification. It MUST NOT add protocol names to the configuration schema.

#### Scenario: UDP Lua receives known payload

- **WHEN** a TUN Lua strategy processes a recognized UDP payload
- **THEN** its protocol and payload subtype correspond to the existing classifier rather than Unknown.

#### Scenario: QUIC host filter rejects a different host

- **WHEN** a QUIC Initial has a hostname outside the configured host filter
- **THEN** the interceptor forwards it without an injected mutation.

### Requirement: REQ-UDP-003 — Preserve deployment boundaries

The change MUST retain non-root proxy Lua operation, existing root injection constraints, socket protection, and local-only processing. It MUST NOT introduce a new external dependency or record packet contents.

#### Scenario: Non-root ownership remains with the proxy

- **WHEN** the proxy owns Lua socket execution
- **THEN** the TUN interceptor does not execute the same Lua steps a second time.
