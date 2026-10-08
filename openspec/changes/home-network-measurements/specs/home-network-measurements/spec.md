## Purpose

Distinguish actual network connectivity and resolver configuration from DNS answers.

## ADDED Requirements

### Requirement: REQ-HOME-NET-001 — Measure IPv6 connection

The implementation MUST determine IPv6 reachability with a bounded connection to a numeric IPv6 address, without using AAAA lookup as proof. The socket MUST be bound to the captured network. The result MUST be unknown if that network is absent or changes during the probe.

#### Scenario: IPv6 connect fails

- **WHEN** the IPv6 TCP connection fails or times out
- **THEN** Home does not report IPv6 as reachable.


#### Scenario: Network changes during probe

- **WHEN** the captured network is no longer active when the bound socket completes
- **THEN** Home reports unknown IPv6 reachability and closes the socket.

### Requirement: REQ-HOME-NET-002 — Report configured resolver

The implementation MUST use the active network DNS server address for systemResolver, or null if unavailable.

#### Scenario: No resolver metadata

- **WHEN** DNS server metadata is unavailable
- **THEN** Home reports no resolver address.
