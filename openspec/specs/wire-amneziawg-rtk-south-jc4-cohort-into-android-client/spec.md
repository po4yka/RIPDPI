# wire-amneziawg-rtk-south-jc4-cohort-into-android-client Specification

## Purpose

Define Android AmneziaWG cohort profile import, bounded startup retries, and verification with a synthetic endpoint.

## Requirements

### Requirement: REQ-TRN-1786264762917677-001 — AmneziaWG client support compiles for all 4 Android ABIs

The RIPDPI implementation MUST satisfy this portfolio criterion: AmneziaWG client support compiles for all 4 Android ABIs.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that AmneziaWG client support compiles for all 4 Android ABIs

### Requirement: REQ-TRN-1786264762917677-002 — Import AmneziaWG cohort parameters

The RIPDPI implementation MUST satisfy this portfolio criterion: Cohort profile import populates Jc/Jmin/Jmax/S/H/I from server-provided YAML or subscription URL.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Cohort profile import populates Jc/Jmin/Jmax/S/H/I from server-provided YAML or subscription URL

### Requirement: REQ-TRN-1786264762917677-003 — Smoke test against synthetic AWG endpoint with RTK South parameters succeeds

The RIPDPI implementation MUST satisfy this portfolio criterion: Smoke test against synthetic AWG endpoint with RTK South parameters succeeds.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Smoke test against synthetic AWG endpoint with RTK South parameters succeeds

### Requirement: REQ-TRN-1786264762917677-004 — Probabilistic-retry logic implemented (max 4 attempts, configurable per-cohort)

The RIPDPI implementation MUST satisfy this portfolio criterion: Probabilistic-retry logic implemented (max 4 attempts, configurable per-cohort).

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Probabilistic-retry logic implemented (max 4 attempts, configurable per-cohort)

### Requirement: REQ-TRN-1786264762917677-005 — Apply cohort parameters in the native WireGuard engine

The Android client MUST apply the cohort parameters to the native AmneziaWG WireGuard engine. The WireGuard WebSocket carrier remains a separate transport.

#### Scenario: Start the native AmneziaWG transport

- **WHEN** the client activates a profile with the cohort parameters
- **THEN** the native AmneziaWG WireGuard engine uses those parameters
