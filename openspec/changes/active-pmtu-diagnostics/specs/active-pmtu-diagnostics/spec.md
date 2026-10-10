## Purpose

Measure active UDP packet sizes with explicit method, family and evidence limits.

## ADDED Requirements

### Requirement: REQ-PMTU-MEASURE — Authenticated active measurements

The probe MUST use certificate-validated QUIC and padded DPLPMTUD probes with fragmentation disabled. IPv4 and IPv6 MUST have separate results. Only ACK-updated payload sizes above the configured initial size can become a measured lower bound.

#### Scenario: Authenticated active measurements

- **WHEN** a real peer acknowledges packets under a size cliff
- **THEN** the report contains the acknowledged UDP payload lower bound and sent/lost probe counts for that family

### Requirement: REQ-PMTU-LIMITS — Bounded interpretation

The report MUST identify QUIC_DPLPMTUD, UDP payload units, configured ceiling and observation-window completion. It MUST NOT call a configured initial value, search ceiling, timeout, peer cap or single loss an exact PMTU or provider block.

#### Scenario: Bounded interpretation

- **WHEN** QUIC cannot establish or the peer limits probing
- **THEN** the result is inconclusive and does not invent an MTU or provider cause

### Requirement: REQ-PMTU-LIFETIME — Bounded network work

The probe MUST enforce deadlines, cancellation, direct-path admission and VPN socket protection. It MUST NOT silently fall back from a proxy path or require root.

#### Scenario: Bounded network work

- **WHEN** a callback stalls, cancellation arrives, or the path is unsupported
- **THEN** the caller returns within its budget and abandoned sockets send no packets

### Requirement: REQ-PMTU-CONTRACT — Compatible and scoped facts

Optional config MUST preserve legacy decoding. Typed evidence MUST validate bounds and consistency, revoke network authority when scope changes and remove addresses/unknown nested fields during redacted export.

#### Scenario: Compatible and scoped facts

- **WHEN** a report has malformed evidence or unverified network scope
- **THEN** it cannot produce an authoritative success or leak nested private fields

### Requirement: REQ-PMTU-UI — Accessible end-to-end display

Manual and full analysis MUST expose results in current, history and copy flows with localized method/limits labels. Quick and background scans MUST exclude the active probe.

#### Scenario: Accessible end-to-end display

- **WHEN** a completed or partial scan is opened in a narrow RTL layout
- **THEN** the same bounded facts are readable without clipping or loss of scope warnings
