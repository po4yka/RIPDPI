# paired-network-availability Specification

## Purpose
Compare two explicit selective-matrix sessions from different networks to separate path-specific failures from unavailable endpoints.

## Requirements

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-PAIR — Pairing

Two sessions MUST be compared only when their target set, probe parameter, and app configuration revisions match, their transport labels differ, and the second started no more than 30 minutes after the first. The comparison MUST NOT store SIM, SSID, or public IP identifiers.

#### Scenario: Revision mismatch

- **WHEN** the user selects a Wi-Fi session and a later cellular session with different target set revisions
- **THEN** the result is comparison unavailable and names the target set mismatch

#### Scenario: Stale reference

- **WHEN** the second session started 45 minutes after the first
- **THEN** the result is comparison unavailable and names the age limit

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-CONTROL — Control rows

A row MUST count as path-specific only when one leg has a verified TLS result and a complete bounded payload result and the other leg fails. Rows that fail on both legs, fail their control, or are not measured MUST be inconclusive and MUST stay out of the denominator.

#### Scenario: Fails on both

- **WHEN** a target fails on both legs
- **THEN** the row is inconclusive and is not counted as blocked

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-SCOPE — Leg validity

A leg during which the selected underlying network changed MUST be invalid. The comparison MUST NOT switch networks, MUST NOT disable VPN protection, and MUST NOT replace an invalid leg with another session.

#### Scenario: Network change during a leg

- **WHEN** the OS changes the selected network during the second leg
- **THEN** the comparison marks that leg invalid and shows no path-specific rows

### Requirement: REQ-PAIRED-NETWORK-AVAILABILITY-EVIDENCE — Evidence

The result and the redacted export MUST show per-leg stage outcomes and repetition counts as observed differences, and MUST NOT claim permitted prefixes, ASN-wide availability, or nationwide results.

#### Scenario: One path succeeds

- **WHEN** a target completes its payload on Wi-Fi and fails on cellular with valid controls
- **THEN** the row shows a path-specific difference with both timestamps and no allowlist claim
