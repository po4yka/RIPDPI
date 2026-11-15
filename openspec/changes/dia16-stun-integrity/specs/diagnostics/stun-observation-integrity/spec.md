## Purpose

Keep STUN observations consistent with network consent, actual path ownership, valid wire messages, and explicit evaluation coverage.

## ADDED Requirements

### Requirement: REQ-DIA16-CONSENT — Honor network consent

The implementation MUST skip WebRTC STUN when active network checks are disabled.

#### Scenario: Honor network consent

- **GIVEN** Network checks are disabled
- **WHEN** the user starts Detection Check
- **THEN** no WebRTC STUN transaction starts

### Requirement: REQ-DIA16-PATH — Record actual path ownership

The implementation MUST label a default application socket as APP_DEFAULT and MUST preserve an explicit empty supported path set. It MUST NOT claim VPN ownership without a controlled VPN transport.

#### Scenario: Record actual path ownership

- **GIVEN** the application uses its normal socket
- **WHEN** the default STUN client returns an address
- **THEN** the observation identifies APP_DEFAULT and does not claim VPN

### Requirement: REQ-DIA16-WIRE — Validate the complete STUN message

The implementation MUST validate the complete header, transaction, body, TLV boundaries, address lengths, and unknown required attributes before it accepts an address. It MUST retain valid IPv4, IPv6, and explicit legacy MAPPED extraction.

#### Scenario: Validate the complete STUN message

- **GIVEN** a matching message contains a valid XOR address followed by an invalid TLV
- **WHEN** the client parses the response
- **THEN** the complete response is rejected

### Requirement: REQ-DIA16-PEER — Match the response peer and envelope

The implementation MUST accept responses only from the selected UDP peer. A SOCKS5 response MUST have valid reserved bytes, fragment, address framing, and origin metadata. Unrelated packets MUST NOT end an otherwise active transaction.

#### Scenario: Match the response peer and envelope

- **GIVEN** an unrelated or malformed packet precedes a valid response
- **WHEN** the valid response arrives before the deadline
- **THEN** only the valid matching response becomes evidence

### Requirement: REQ-DIA16-CLASSIFY — Keep divergence separate from proof of a leak

The implementation MUST NOT classify address differences alone as a high-confidence leak. It MUST preserve path provenance and compare numeric addresses rather than string spelling.

#### Scenario: Keep divergence separate from proof of a leak

- **GIVEN** VPN and underlying have different expected exit addresses
- **WHEN** the checker compares observations
- **THEN** it records an observation without a high-confidence leak

### Requirement: REQ-DIA16-OUTCOME — Report incomplete evaluation explicitly

The implementation MUST distinguish complete evaluation from disabled, unsupported, unavailable, and not-evaluated results. UI and reports MUST NOT show an incomplete result as OK.

#### Scenario: Report incomplete evaluation explicitly

- **GIVEN** no valid response was obtained or a comparison path is unavailable
- **WHEN** the result is rendered or exported
- **THEN** the result contains its incomplete evaluation state

### Requirement: REQ-DIA16-CANCEL — Release cancelled network work

The implementation MUST close probe sockets on cancellation and bound the DNS and network stages. A cancelled request MUST NOT start the next STUN request.

#### Scenario: Release cancelled network work

- **GIVEN** a probe is waiting for a UDP response
- **WHEN** the caller cancels it
- **THEN** the probe completes cancellation promptly and closes its socket

