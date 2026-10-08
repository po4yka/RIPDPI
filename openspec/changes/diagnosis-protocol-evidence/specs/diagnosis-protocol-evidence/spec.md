## Purpose

Keep diagnosis confidence and wording within the protocol evidence collected by a scan.

## ADDED Requirements

### Requirement: REQ-DIAG-PROTOCOL-EVIDENCE — Scope diagnosis evidence

The implementation MUST validate a diagnosis only with executed controls for its protocol and MUST describe failure without unsupported cause attribution.

#### Scenario: Unrelated or absent controls

- **WHEN** TLS controls pass but DNS, QUIC or HTTP controls were not executed
- **THEN** their diagnoses retain unknown control validation.

#### Scenario: Matched control outcomes

- **WHEN** executed TLS or HTTP controls fail
- **THEN** matching diagnoses have false control validation and unexecuted controls are ignored.

#### Scenario: Differential measurements

- **WHEN** ECH succeeds, throughput differs or an advertised HTTP/3 endpoint fails the QUIC probe
- **THEN** summaries state the measured difference without claiming provider blocking.

#### Scenario: TLS and QUIC failures

- **WHEN** both protocols fail without an SNI comparison
- **THEN** no SNI-triggered diagnosis is emitted.

#### Scenario: Compatibility and privacy

- **WHEN** results are serialized
- **THEN** existing diagnosis codes remain usable and no additional identifiers leave the device.

#### Scenario: Active confirm-good report

- **WHEN** Reality application flows stall and a QUIC Initial response is observed
- **THEN** the active report retains these facts with unknown matched-control validation and the transport recommendation uses an unknown cause with medium confidence.

#### Scenario: DNS comparison evidence

- **WHEN** DNS answers or response latencies differ
- **THEN** summaries describe the difference without claiming record deletion, injected responses or provider-imposed delays.
