## Purpose

Bound synthetic QUIC Initial generation and preserve packet integrity before the original UDP datagram is sent.

## ADDED Requirements

### Requirement: REQ-CID-BUDGET — Bound CID churn work

The implementation MUST reject CID churn counts outside 0..64 and cumulative CID churn counts above 64 in a configured chain. The planner MUST also bound internally constructed chains to 64 CID churn decoys. Other steps retain their current behavior.

#### Scenario: Excessive configuration

- **WHEN** a chain contains count 65, i32 maximum, or two CID churn counts whose sum exceeds 64
- **THEN** Kotlin and native validation reject it before packet generation.

#### Scenario: Internal defensive bound

- **WHEN** an internal chain bypasses validation with excessive counts
- **THEN** at most 64 CID churn decoys are generated and the original payload remains in the plan.

### Requirement: REQ-CID-UNIQUE — Distinct IDs in one plan

The implementation MUST use distinct DCIDs across all CID churn decoys in one plan and derive Initial protection from the emitted DCID. Series reset between plans remains supported. It MUST NOT copy the real authority or session secrets into the decoy.

#### Scenario: Previous collision boundaries

- **WHEN** v1 or v2 decoys reach the previous u8 wrap or zero-mask boundaries, or a plan has two CID churn steps
- **THEN** each generated DCID is distinct and independent packet decoding succeeds.

### Requirement: REQ-CID-LENGTH — Exact padded Initial size

The implementation MUST account for the final QUIC Length field width when padding an Initial. A target that can contain the unpadded packet MUST produce that exact datagram size, including transition-gap values and 65507 bytes. A valid maximum IPv4 input MUST NOT gain bytes in its CID churn prelude.

#### Scenario: Varint and transport boundaries

- **WHEN** an Initial is generated at a Length width boundary or at the IPv4 UDP payload maximum
- **THEN** the emitted packet has the requested size, independent decoding succeeds, and loopback execution sends decoys followed by the unchanged original.

### Requirement: REQ-CID-SCOPE — Preserve activation and transport limits

The implementation MUST preserve group and step activation, zero-count no-op, malformed-input fallback, original bytes, relay skip behavior, and non-root operation. It MUST NOT introduce live migration, NEW_CONNECTION_ID state, external probes, or schema changes.

#### Scenario: Inactive or unsupported input

- **WHEN** the activation filter does not match or no complete normalized Initial is available
- **THEN** no CID churn decoy is emitted and the original datagram is preserved.
