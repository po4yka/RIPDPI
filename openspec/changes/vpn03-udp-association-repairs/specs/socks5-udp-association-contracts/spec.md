## Purpose

Repair UDP destination ownership, receive boundaries, and ingress validation in the Android TUN bridge.

## ADDED Requirements

### Requirement: REQ-VPN03-REPLY-OWNERSHIP — Retain accepted reply authority

The tunnel MUST retain the response identity of each admitted UDP destination until its destination lifetime ends. It MUST reject a new destination before sending when the destination limit is full. It MUST NOT remove a live reply mapping to admit another destination.

#### Scenario: Full destination table

- **WHEN** one source sends to more destinations than its bounded table permits
- **THEN** accepted destinations keep their response source addresses
- **AND** excess destinations are rejected before outbound delivery.

### Requirement: REQ-VPN03-LEASE-LIFETIME — Expire idle destinations independently

The tunnel MUST bound DNS mapping ownership per UDP source. An idle destination MUST release its mapping lease independently of activity at another destination. Queued or late responses from a retired destination MUST NOT use a reassigned synthetic mapping.

#### Scenario: Churn with one active destination

- **WHEN** old destinations expire while one destination remains active
- **THEN** old mapping leases become available for new DNS answers
- **AND** a queued old response cannot reach the TUN through a reassigned mapping.

### Requirement: REQ-VPN03-COMPLETE-DATAGRAM — Preserve complete normal UDP messages

The tunnel MUST distinguish a complete receive from a truncated frame. It MUST accept a complete exact-fit frame within the supported datagram limit. It MUST deliver large normal UDP replies as valid IPv4 or IPv6 packets no larger than the configured TUN MTU. Receive storage MUST have explicit aggregate bounds.

#### Scenario: Large reply and exact fit

- **WHEN** a valid UDP reply exceeds the TUN MTU or exactly fills the permitted receive size
- **THEN** the caller receives its complete payload with valid IP and UDP checksums
- **AND** no truncated prefix is delivered as a complete datagram.

### Requirement: REQ-VPN03-CHECKSUM — Validate UDP ingress checksums

The tunnel MUST discard invalid UDP checksums before admitting an association. IPv4 UDP checksum zero MUST remain permitted. IPv6 UDP checksum zero MUST be rejected in this path.

#### Scenario: Malformed IPv6 UDP

- **WHEN** an IPv6 UDP packet has a zero or incorrect checksum
- **THEN** no association is admitted and no payload reaches the upstream socket.
