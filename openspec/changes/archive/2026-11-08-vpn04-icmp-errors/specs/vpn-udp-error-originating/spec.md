## Purpose

Return truthful local UDP forwarding errors to the originating application with bounded, accurate IP packet quotes.

## ADDED Requirements

### Requirement: REQ-VPN04-QUOTE — Preserve original UDP packet bytes

The implementation MUST quote a validated original IP prefix without rebuilding its headers or UDP checksum. It MUST reject malformed lengths, invalid checksums, fragments, prohibited source/destination addresses and unsupported routing-header checksum semantics. It MUST exclude trailing packet padding.

#### Scenario: Headers differ from serializer defaults

- **WHEN** an IPv4 packet has options or zero UDP checksum, or an IPv6 packet has destination options and nonzero header fields
- **THEN** the quoted prefix equals the original input bytes

#### Scenario: Input cannot produce a safe quote

- **WHEN** a packet is malformed, fragmented, multicast, broadcast, or has an unspecified source
- **THEN** no ICMP error is originated for it

### Requirement: REQ-VPN04-MTU — Bound quotes by the correct family budget

The implementation MUST include up to 1232 bytes of the invoking IPv6 packet in an error with a 40-byte outer header and 8-byte ICMP header. IPv4 errors MUST remain within 576 bytes and include the complete original header and UDP header when admitted.

#### Scenario: IPv6 quote boundary

- **WHEN** original IPv6 lengths are 1231, 1232 and 1233 bytes
- **THEN** quote lengths are 1231, 1232 and 1232 and checksums are valid

### Requirement: REQ-VPN04-LOCAL — Deliver truthful local association errors

For a deterministic SOCKS association setup failure, the implementation MUST return a local Host Unreachable IPv4 error or Address Unreachable IPv6 error for the first successfully queued, admitted datagram when a safe quote and local source exist. It MUST NOT convert a local failure to remote Port Unreachable. It MUST preserve UID and association-generation checks.

#### Scenario: Local association refuses setup

- **WHEN** an admitted IPv4 or IPv6 UDP packet reaches a local SOCKS peer that refuses the association
- **THEN** TUN receives the family-correct local error, sourced from its configured local interface address, with the original quote

#### Scenario: No safe attributed error exists

- **WHEN** setup times out, cancellation wins, a queue is full, a generation is stale, or input was reassembled
- **THEN** no misleading or stale ICMP error reaches TUN

### Requirement: REQ-VPN04-BOUNDS — Bound errors and preserve privacy

The implementation MUST limit generated errors to a burst of 10 and an average of 10 per second. It MUST retain at most one bounded error packet per association, omit payload logs and persistence, and suppress emission after cancellation.

#### Scenario: Error flood and stop

- **WHEN** local failures exceed the error budget or stop occurs with a full event channel
- **THEN** error output remains bounded and workers stop without waiting for undrained events
