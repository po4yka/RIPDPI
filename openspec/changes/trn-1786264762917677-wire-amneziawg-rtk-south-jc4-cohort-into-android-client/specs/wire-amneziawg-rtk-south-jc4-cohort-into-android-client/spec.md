## Purpose

Define the observable completion contract for Wire AmneziaWG RTK South cohort (Jc=4) into Android client. Plain WireGuard on the observed regional network path experiences periodic 20–30 second interruptions every ~30 seconds — middlebox/device fingerprinting can identify WireGuard via the deterministic 148-byte Initiation packet structure (4-byte type, 4-byte sender index, 32-byte ephemeral public key, 48-byte encrypted static key, 28-byte encrypted timestamp, 16-byte MAC1, 16-byte MAC2). AmneziaWG (AWG) randomizes this signature with junk/header/initialization parameters

## ADDED Requirements

### Requirement: REQ-TRN-1786264762917677-001 — AmneziaWG client support compiles for all 4 Android ABIs

The RIPDPI implementation MUST satisfy this portfolio criterion: AmneziaWG client support compiles for all 4 Android ABIs.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that AmneziaWG client support compiles for all 4 Android ABIs

### Requirement: REQ-TRN-1786264762917677-002 — Cohort profile import populates Jc/Jmin/Jmax/S/H/I from server-provided YAML or…

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

### Requirement: REQ-TRN-1786264762917677-005 — Dedup confirmed: distinct from add-wireguard-over-websocket-transport-amneziawg…

The RIPDPI implementation MUST satisfy this portfolio criterion: Dedup confirmed: distinct from add-wireguard-over-websocket-transport-amneziawg-disguise — this task wires AmneziaWG packet-signature randomization (Jc/Jmin/Jmax/H/S/I) into the existing ripdpi-warp-core WG kernel; the other adds a WG-over-WebSocket tunnel di….

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Dedup confirmed: distinct from add-wireguard-over-websocket-transport-amneziawg-disguise — this task wires AmneziaWG packet-signature randomization (Jc/Jmin/Jmax/H/S/I) into the existing ripdpi-warp-core WG kernel; the other adds a WG-over-WebSocket tunnel di…
