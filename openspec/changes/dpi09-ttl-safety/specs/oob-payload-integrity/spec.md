## Purpose

Keep Disoob execution and its evidence accurate when socket operations fail or
a send queue remains blocked. Preserve the existing OOB byte-stream contract.

## ADDED Requirements

### Requirement: REQ-OOB-TTL-RESTORE — Stop after a failed TTL restore

A failed restoration of a lowered socket TTL MUST fail the current send and
retain accepted payload progress. It MUST NOT be reported as a successful
TTL-unavailable fallback. The implementation MUST NOT send the tail after this
failure. Initial TTL unavailability MAY retain the existing Oob fallback.

#### Scenario: Restoration fails after progress

- **WHEN** a lowered socket rejects restoration after original bytes were accepted
- **THEN** the send reports the restoration error and the accepted progress
- **AND** the caller cannot replay the original payload as uncommitted data

### Requirement: REQ-OOB-TTL-FAMILY — Set the active address family

A socket TTL operation MUST change IPv4 TTL for an IPv4 peer or IPv6 Hop Limit
for an IPv6 peer. Success in another address family MUST NOT hide a failure.

#### Scenario: IPv6 socket has independent IPv4 and IPv6 values

- **WHEN** an IPv6 peer socket receives a requested Hop Limit change
- **THEN** the IPv6 value changes and its inactive IPv4 TTL is not changed

### Requirement: REQ-OOB-STAGE-DEADLINE — Fail an undrained stage

A send-stage wait MUST return a timeout when its deadline expires while bytes
remain unsent, with or without an initial wait interval.

#### Scenario: Peer does not drain the send queue

- **WHEN** the send queue remains nonempty until the stage deadline
- **THEN** both wait modes return TimedOut instead of reporting completion

### Requirement: REQ-OOB-WIRE-EVIDENCE — Verify the actual Disoob stream

The Disoob packet-smoke check MUST require a strict local echo, original payload
bytes plus one urgent byte at the configured sequence position, TTL 1 for the
lowered prefix and urgent byte, and the restored TTL for the tail. It MUST allow
separate prefix and urgent TCP segments and MUST reject gaps or conflicting bytes.
It MUST NOT treat fake TTL, a family log or unrelated packets as this evidence.

#### Scenario: Prefix and urgent byte use separate segments

- **WHEN** the emitter sends the prefix and one urgent byte in separate segments
- **THEN** the oracle verifies their sequence positions and their TTL values
- **AND** the local application receives exactly the original payload

### Requirement: REQ-OOB-PLATFORM-SCOPE — State the supported stage platforms

Disoob documentation MUST identify Linux and Android as the native stage-wait
platforms. It MUST NOT claim full execution support from a host test adapter.

#### Scenario: A host lacks native send-stage support

- **WHEN** a user reads the Disoob platform table
- **THEN** it states the Linux and Android boundary and the host-test limitation
