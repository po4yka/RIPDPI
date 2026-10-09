## Purpose

Keep VPN runtime recovery tied to observed physical path changes while separating transient direct DNS lease observation from physical handover evidence.

## ADDED Requirements

### Requirement: REQ-HANDOVER-PHYSICAL — Observe the physical network

The handover monitor MUST compare the physical path separately from transient underlay lease acquisition or loss. A lease marker becoming present or absent alone MUST NOT restart the provider or rebuild its tunnel when all physical inputs are equal.

#### Scenario: Default callback changes while the physical path is stable

- **GIVEN** the physical path is unchanged and the provider is running
- **WHEN** the default callback changes and the transient direct DNS lease marker becomes absent
- **THEN** that marker loss alone does not cause a physical handover event
- **AND** the provider and real tunnel remain active

### Requirement: REQ-HANDOVER-BASELINE — Initialize observation consistently

The monitor MUST initialize its physical path observation consistently with its observed underlay. Initial callback or lease state population without a physical path change MUST NOT produce a false link refresh. It MUST retain subsequent genuine underlay changes.

#### Scenario: Initial observation receives callback state

- **GIVEN** the physical network is already active when observation starts
- **WHEN** the initial callback state becomes available
- **THEN** the monitor records a baseline without a false runtime restart

### Requirement: REQ-HANDOVER-COMPATIBILITY — Retain physical recovery and privacy

The monitor MUST retain recovery events for genuine physical transport, validation, captive portal, DNS, link, and underlay lease changes. It MUST keep the fingerprint key recipe, redaction, routing security, and stored contracts unchanged. Equal scope hashes alone MUST NOT suppress physical recovery.

#### Scenario: Physical path changes while VPN is active

- **GIVEN** the VPN overlay is active
- **WHEN** the physical network or its recovery-relevant state changes
- **THEN** the monitor emits the existing applicable handover event
- **AND** it does not persist or log raw network identifiers

#### Scenario: Valid underlay generation changes

- **GIVEN** both observations contain a valid underlay generation
- **WHEN** the generation changes even though the other physical fields are equal
- **THEN** the existing link refresh event remains eligible

### Requirement: REQ-HANDOVER-RUNTIME — Verify real DNS and peer recovery

Acceptance MUST verify encrypted DNS and distinct-UID payload through the real provider before peer loss and after recovery. It MUST retain failed runs and prove resolver, tunnel, server receipts, direct bypass rejection, and cleanup.

#### Scenario: Stable baseline precedes a peer outage

- **GIVEN** the real provider has started on the physical path
- **WHEN** the baseline DNS and payload probes run and the peer later stops and returns
- **THEN** owned receipts prove baseline and recovered traffic
- **AND** lifecycle observations separate physical recovery from peer failure and VPN feedback
