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

Before its first accepted response, a supported QUIC flow MUST require a correlated, integrity-valid Initial or Retry before success, session acceptance, or migration. An outbound follow-up MUST NOT remove the retained request. Unrelated replies MUST remain forwarded without refreshing response state.

#### Scenario: A reply belongs to the decoy

- **WHEN** VN echoes only the decoy connection IDs
- **THEN** it does not mark the original flow successful

#### Scenario: A valid reply arrives

- **WHEN** a correlated v1 or v2 Initial or Retry passes integrity checks
- **THEN** success is recorded and the retained Initial is released

### Requirement: REQ-DPI24-COMPAT — Preserve compatible version replies

The runtime MUST allow a different v1/v2 Initial version only when the complete client offer permits it. Retry MUST use the original version. If a compatible Initial has valid CID and integrity but the offer is unavailable, it MUST remain unverified: forward it without success or session acceptance; idle expiry MUST NOT learn failure or trigger fallback.

#### Scenario: A compatible version was announced

- **WHEN** a CID- and integrity-valid v2 Initial answers a v1 request that advertised v2, or the inverse pair
- **THEN** the runtime accepts it without changing the active single-version probe validator

#### Scenario: A compatible reply has an incomplete offer

- **WHEN** a compatible v1/v2 Initial has valid CID and integrity but its client offer is unavailable
- **THEN** it is forwarded without success accounting and idle expiry is neutral

### Requirement: REQ-DPI24-GREASE — Preserve greased replies

The runtime MUST accept a CID- and integrity-valid Initial or original-version Retry with Fixed Bit zero when the complete client offer contains one empty grease_quic_bit parameter. If the offer is unavailable, the reply MUST remain unverified and idle expiry MUST be neutral. The active probe validator MUST retain its existing contract.

#### Scenario: The client permits a cleared Fixed Bit

- **WHEN** a valid server Initial or Retry clears the Fixed Bit after the client offers grease_quic_bit
- **THEN** the runtime accepts it using the original header bytes for integrity verification

#### Scenario: The offer is unavailable

- **WHEN** an integrity-valid reply clears the Fixed Bit but the retained ClientHello is incomplete
- **THEN** it is forwarded without success accounting and idle expiry is neutral

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
