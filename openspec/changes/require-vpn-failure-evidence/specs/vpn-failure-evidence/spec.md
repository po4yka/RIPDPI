## Purpose

Require explicit evidence before reporting a previous VPN session.

## ADDED Requirements

### Requirement: REQ-DGN-VPN-001 — Preserve measured VPN evidence

The collector MUST preserve the snapshot vpnServiceWasActive value. It MUST NOT infer prior VPN activity from Halted/VPN service state.

#### Scenario: Offline first launch

- **WHEN** transport is none and service state is the default Halted/VPN
- **THEN** the collector leaves vpnServiceWasActive false

#### Scenario: Explicit evidence

- **WHEN** the snapshot contains vpnServiceWasActive true
- **THEN** the collector preserves that evidence
