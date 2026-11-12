# wg-ws-controls Specification

## Purpose
Keep normal WebSocket control traffic inside the datagram carrier and preserve binary WireGuard delivery across control messages.

## Requirements

### Requirement: REQ-WS-CONTROL — Handle Ping and Pong

The carrier MUST answer Ping, ignore Pong as a datagram, and return the next binary message without an application error.

#### Scenario: Controls precede a datagram

- **WHEN** the peer sends a valid Ping and unsolicited Pong before binary data
- **THEN** the carrier sends a matching masked Pong and returns only the binary payload

#### Scenario: Writer remains blocked

- **WHEN** a pending sender prevents Pong from being written and Ping is followed by binary data and Close
- **THEN** the receiver returns the data and observes Close with its original bounded reply deadline
