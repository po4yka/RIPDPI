# TCP IP fragmentation

## Purpose

Preserve application data and IP packet contracts when TCP IP fragmentation is selected or cannot run safely.

## ADDED Requirements

### Requirement: REQ-DPI14-F1 — Isolate TCP send steps

The implementation MUST reject ipfrag2 chains that contain another TCP send step while it permits preceding TLS preludes.

#### Scenario: Isolate TCP send steps

- **WHEN** a split precedes ipfrag2
- **THEN** configuration validation fails before network use.

### Requirement: REQ-DPI14-F2 — Account for emitted fragments

The implementation MUST record successfully emitted TCP payload in the repair send queue without a second ordinary initial write.

#### Scenario: Account for emitted fragments

- **WHEN** both raw fragments are sent
- **THEN** the TCP send queue advances without an ordinary full-payload initial send.

### Requirement: REQ-DPI14-F3 — Preserve uncertain progress

The implementation MUST mark any unclassified in-flight fragment failure as possibly committed and prohibit fallback or replay after possible emission.

#### Scenario: Preserve uncertain progress

- **WHEN** a sender writes bytes and then fails
- **THEN** the failure cannot report a safe zero-byte send.

### Requirement: REQ-DPI14-F4 — Enforce fragmentation eligibility

The implementation MUST use an ordinary full-payload write outside round one or at a non-internal offset.

#### Scenario: Enforce fragmentation eligibility

- **WHEN** an explicit range enables round two
- **THEN** no fragmented sender is called.

### Requirement: REQ-DPI14-F5 — Preserve IPv6 profiles

The implementation MUST carry configured IPv6 extensions and fragment order through the runtime and a matching version-four helper.

#### Scenario: Preserve IPv6 profiles

- **WHEN** a nondefault IPv6 profile is selected
- **THEN** the emitted packet builder receives the same extension fields.

### Requirement: REQ-DPI14-F6 — Preserve helper error semantics

The implementation MUST preserve validated operation error kind, errno and emission uncertainty across helper IPC.

#### Scenario: Preserve helper error semantics

- **WHEN** a helper reports a proven pre-send busy queue
- **THEN** the client can apply the same safe policy as the local sender.

### Requirement: REQ-DPI14-F7 — Bound privilege and MTU fallback

The implementation MUST choose fallback only for a proven pre-send capability or MTU rejection and retain VPN protection failures.

#### Scenario: Bound privilege and MTU fallback

- **WHEN** one fragment exceeds the route MTU
- **THEN** no fragment is sent before the rejection.

### Requirement: REQ-DPI14-F8 — Reserve reassembly identifiers

The implementation MUST reserve nonzero fragmented IPv4 IDs by source IP, destination IP and protocol without reuse during the retention interval.

#### Scenario: Reserve reassembly identifiers

- **WHEN** two TCP ports share an IP reassembly tuple
- **THEN** their live fragment reservations differ.

### Requirement: REQ-DPI14-F9 — Enforce original packet lengths

The implementation MUST reject a fragmented original IPv4 total length or IPv6 payload length above its non-jumbo protocol limit.

#### Scenario: Enforce original packet lengths

- **WHEN** the reassembled original exceeds the limit by one byte
- **THEN** the builder returns an error before emission.
