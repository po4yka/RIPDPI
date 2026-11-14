## Purpose

Bound DPI-24 resources and keep response accounting and names consistent with the actual packet format.

## ADDED Requirements

### Requirement: REQ-DPI24-COUNT — Bound repeat allocation

Kotlin and Rust MUST reject DPI-24 counts outside 0 through 64 and a combined DPI-24 count above 64 in one UDP chain. Internal planning MUST bound copies and preserve the original datagram.

#### Scenario: Repeated steps exceed the limit

- **WHEN** several DPI-24 steps have a total count above 64
- **THEN** Kotlin and Rust reject the configuration before packet allocation

#### Scenario: Unsafe internal count

- **WHEN** an internal caller supplies an excessive count
- **THEN** planning retains at most 64 DPI-24 packets and preserves the original payload

### Requirement: REQ-DPI24-CORRELATION — Require correlated success

Before its first accepted response, a supported QUIC flow MUST require a correlated Initial or Retry with valid integrity before success, session acceptance, migration, or timeout suppression. A later outbound datagram MUST NOT remove this requirement. Transparent UDP forwarding MUST remain available.

#### Scenario: A reply belongs to the decoy

- **WHEN** VN echoes only the decoy connection IDs
- **THEN** it does not mark the original flow successful

#### Scenario: A valid reply arrives

- **WHEN** a correlated v1 or v2 Initial or Retry passes integrity checks
- **THEN** success is recorded and the retained Initial is released

### Requirement: REQ-DPI24-PROBE — Keep one receive deadline

QUIC probes MUST ignore unrelated replies and accept a later correlated reply within the original deadline. Generic UDP MUST retain first-peer-response behavior.

#### Scenario: Unrelated response first

- **WHEN** an unrelated VN precedes a valid correlated response
- **THEN** the probe accepts the valid response without a new timeout budget

#### Scenario: Continuous unrelated responses

- **WHEN** the peer sends only unrelated responses
- **THEN** the probe ends at the original deadline

### Requirement: REQ-DPI24-NAME — Describe the version XOR decoy

UI and documentation MUST describe an Initial-shaped version XOR decoy. The saved ID quic_version_negotiation_decoy and old report aliases MUST remain readable. Documentation MUST distinguish raw header mutation from protected Initial generation and version=0 VN.

#### Scenario: An old setting or report is loaded

- **WHEN** a saved ID or old human-readable name is loaded
- **THEN** it resolves to the same family with the corrected displayed name
