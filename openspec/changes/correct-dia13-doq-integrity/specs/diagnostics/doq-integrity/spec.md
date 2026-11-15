## Purpose

Report what the Android IN/A diagnostic establishes about the DoQ exchange and its comparison with encrypted DNS observations.

## ADDED Requirements

### Requirement: REQ-DGN-1794744781459086-001 — Preserve DNS outcomes

The diagnostic MUST preserve the DNS outcome and MUST require usable comparable evidence for a positive integrity verdict.

#### Scenario: No usable baseline

- **WHEN** a DoQ answer arrives with no usable DoH observation
- **THEN** the result shows the observed DNS outcome and an inconclusive comparison

### Requirement: REQ-DGN-1794744781459086-002 — Compare attributable DNS observations

The diagnostic MUST keep the DoH lanes distinct and MUST distinguish exact agreement, weaker overlap, disagreement and non-comparable evidence.

#### Scenario: Conflicting baselines

- **WHEN** available comparable observations conflict
- **THEN** an overlap with one observation does not establish full agreement

### Requirement: REQ-DGN-1794744781459086-003 — Show DoQ coverage and results

The dashboard and suite MUST report DoQ coverage and results. A suite with known DoQ disagreement or incomplete selected DoQ measurements MUST NOT report a clean result.

#### Scenario: DoQ disagrees with a confirmed baseline

- **WHEN** DoQ diverges and the other DNS lanes agree
- **THEN** the suite shows the DoQ finding and an incomplete or attention result

### Requirement: REQ-DGN-1794744781459086-004 — Preserve failure causes

The exchange MUST preserve meaningful local, bootstrap, TLS, ALPN, QUIC, wire and timeout failure kinds. A single uncorroborated exchange failure MUST NOT imply DPI.

#### Scenario: Certificate identity fails

- **WHEN** the certificate name does not match
- **THEN** the tool reports a TLS failure

### Requirement: REQ-DGN-1794744781459086-005 — Reserve time for alternate bootstrap addresses

The one-shot diagnostic MUST share one absolute deadline across stages and leave time for another known address when an earlier address stalls.

#### Scenario: A first address stalls

- **WHEN** the first address is silent and the next address can answer inside the total budget
- **THEN** the probe can use the second address and return the answer

### Requirement: REQ-DGN-1794744781459086-006 — Enforce framing and message size

The bridge MUST validate one exact local DNS frame before lookup and MUST accept only payloads from 12 to 65535 bytes. The wire exchange MUST keep the RFC 9250 length and ID behavior.

#### Scenario: Input has trailing bytes

- **WHEN** local input exceeds the length in its prefix
- **THEN** the bridge rejects the input before exchange

### Requirement: REQ-DGN-1794744781459086-007 — Bound and cancel diagnostic work

Cancellation MUST stop the native exchange and release capacity when it ends. The silent stub-address preflight MUST avoid DoQ and return on its own deadline. Blocking bootstrap calls MUST remain bounded after cancellation.

#### Scenario: Stub-address preflight reaches its deadline

- **WHEN** the preflight runs out of time without stub addresses
- **THEN** the selected diagnostic proceeds with an empty set and no DoQ preflight
