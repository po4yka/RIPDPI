## Purpose

Provide bounded reproducible network comparison evidence without overstating censorship attribution or exposing private network identities.

## ADDED Requirements

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-PAIR — Pair

The implementation MUST pair two explicit manual sessions only when target set, endpoint identity, probe parameters and app configuration revisions match, recording elapsed age and transport labels without retaining SIM, SSID or public-IP identifiers.

#### Scenario: Pair evidence

- **WHEN** a user selects a Wi-Fi reference and a later mobile session with mismatched target revisions
- **THEN** show comparison unavailable and identify the mismatch rather than produce a blocking verdict

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-CONTROL — Control

The implementation MUST use verified TLS and bounded complete application payload observations as eligibility controls, retaining control-failed and unmeasured rows outside the denominator of path-specific failures.

#### Scenario: Control evidence

- **WHEN** a target fails on both paths or the reference is stale
- **THEN** classify it as inconclusive rather than blocked by an allowlist

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-SCOPE — Scope

The implementation MUST reuse the existing matrix result and history models; network transitions during either leg invalidate that leg. Do not switch networks automatically or disable VPN protection to force a result.

#### Scenario: Scope evidence

- **WHEN** the OS changes the selected underlying network during a leg
- **THEN** the comparison marks the leg invalid and does not silently substitute another path

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-EVIDENCE — Evidence

The implementation MUST present descriptive differences only, preserving per-leg stage outcomes and bounded repetition evidence in redacted exports. Do not infer permitted prefixes, ASN-wide availability or nationwide effectiveness.

#### Scenario: Evidence evidence

- **WHEN** a complete controlled payload succeeds on one path only
- **THEN** display a path-specific observed difference with timestamp, limitations and no universal allowlist claim
