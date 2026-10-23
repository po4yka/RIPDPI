## Purpose

Explain the existing authenticated QUIC path-size evidence without confusing a
raw path, a relay path, a configured ceiling or a tunnel configuration value.

## ADDED Requirements

### Requirement: REQ-QUALITY-PMTU — Preserve measured scope and lower bounds

The existing PMTU diagnostic MUST preserve measured scope and distinguish acknowledged family-specific packet lower bounds from configuration or inference.

#### Scenario: Required measurement contract

- **WHEN** PMTU evidence is presented
- **THEN** The existing PMTU diagnostic MUST expose acknowledged UDP payload lower bounds and separately derived IPv4/IPv6 packet lower bounds using 28/48 header bytes. Only current, authenticated and scope-valid evidence MAY carry measured authority. Configured ceilings, single losses, partial observations, proxy-unsupported paths and failed handshakes MUST remain distinct from measured lower bounds. The UI MUST retain a manual entry point and MUST NOT infer MTU from TCP quality, silently run a raw probe in background quality selection or automatically change AWG MTU.

#### Scenario: Acknowledged family-specific packets

- **WHEN** current raw-path QUIC evidence acknowledges a UDP payload size
- **THEN** the UI shows the payload lower bound and the correctly derived IP packet lower bound for that family

#### Scenario: Scope invalidation or configured ceiling

- **WHEN** network scope is unverified or only the configured upper bound is known
- **THEN** it is not presented as the selected profile's measured MTU
