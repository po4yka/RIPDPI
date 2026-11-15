## Purpose

Compare first-flight responses to synthetic QUIC v1 Initial and Version Negotiation probes on one selected peer.

## ADDED Requirements

### Requirement: REQ-QUIC-TEMPLATE-ATTRIBUTION — Preserve the attribution boundary

The suite MUST report asymmetric template replies as a difference in observed responses. It MUST leave the DPI verdict unchanged by this difference.

#### Scenario: One template does not receive a reply

- **WHEN** the other templates receive valid replies and one template times out
- **THEN** the report shows incomplete results with the same policy for each v1 template

### Requirement: REQ-QUIC-TEMPLATE-RECEIVE — Wait for a correlated reply

The exchange MUST keep the connected UDP socket open until a correlated reply arrives, the receive limit is reached, the deadline expires, or the caller cancels.

#### Scenario: A datagram from the selected peer does not match

- **WHEN** an unrelated datagram precedes a valid correlated reply within the limits
- **THEN** the exchange records the valid reply and retains evidence of the unrelated datagram

### Requirement: REQ-QUIC-TEMPLATE-WIRE — Validate the first-flight datagram

The validator MUST distinguish Initial with CRYPTO, Initial with ACK, other valid Initial, transport close, Retry, and Version Negotiation. It MUST reject invalid Initial frames and an ack-eliciting Initial in a UDP datagram smaller than 1200 bytes.

#### Scenario: A valid Retry arrives before the server processes the ClientHello

- **WHEN** the peer returns a correlated Retry with a valid integrity tag
- **THEN** the report identifies a Retry and does not record an Initial with CRYPTO

### Requirement: REQ-QUIC-TEMPLATE-PEER — Compare attempts on one peer

The runner MUST resolve the target once and use the same address and port for templates and repeats. It MUST keep earlier evidence when a later attempt fails locally. It MUST bound active probes and their queues and stop cancelled exchanges.

#### Scenario: A local error follows a valid response

- **WHEN** packet generation or the socket fails for one template after another valid response
- **THEN** the result includes the valid response and the local failure

### Requirement: REQ-QUIC-TEMPLATE-PRESENTATION — Report the measured state

The report MUST distinguish accepted responses, missing or invalid replies, and no measurement. A displayed delay MUST identify the corresponding reply.

#### Scenario: Version Negotiation does not arrive

- **WHEN** the reserved-version probe receives no correlated response
- **THEN** the report shows an unobserved VN reply without attributing a refusal to the server

### Requirement: REQ-QUIC-TEMPLATE-NAMES — Identify synthetic templates

The UI MUST identify the three v1 probes as synthetic templates and identify the reserved-version probe separately. It MUST describe the measurement as QUIC Initial and Version Negotiation.

#### Scenario: The DPI suite presents the result

- **WHEN** the user selects or reviews this probe
- **THEN** the card does not claim browser or HTTP/3 compatibility

### Requirement: REQ-QUIC-TEMPLATE-TARGET — Validate the complete endpoint

The QUIC target path MUST accept valid DNS names and IPv4 and IPv6 literals. It MUST reject malformed names before sending and MUST omit SNI for numeric literals.

#### Scenario: The user enters a valid IPv6 literal

- **WHEN** the user submits a bracketed or bare IPv6 address
- **THEN** the probe uses the complete address and UDP port 443
