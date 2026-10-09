## Purpose

Provide reproducible local VPN acceptance with explicit protocol, Android, routing, and evidence boundaries.

## ADDED Requirements

### Requirement: REQ-LAB-CATALOG — Explicit coverage

The runner MUST validate unique scenario identities, protocol capabilities, evidence tiers, required artifacts, and executable adapters. External-provider requirements MUST remain distinct from local results.

#### Scenario: Explicit coverage control

- **WHEN** a manifest has unknown adapters, duplicate scenarios, or missing mandatory coverage
- **THEN** validation fails before execution.

### Requirement: REQ-LAB-EVIDENCE — Strict evidence

The runner MUST bind results to the run ID, scenario, source revision, and artifact hashes; missing, skipped, stale, or failed mandatory evidence MUST prevent acceptance.

#### Scenario: Strict evidence control

- **WHEN** an adapter exits zero without complete matching evidence
- **THEN** the report records failure and the process exits nonzero.

### Requirement: REQ-LAB-ROUTER — Controlled network path

The lab MUST create run-owned Linux routing and faults, prove TCP and UDP baseline/drop/recovery, and preserve unrelated network state during cleanup.

#### Scenario: Controlled network path control

- **WHEN** a fault is enabled on the selected path
- **THEN** counters identify the affected flow and removal restores service.

### Requirement: REQ-LAB-ANDROID — Real Android traffic

Android acceptance MUST use real native libraries and separate-UID traffic with server receipts, wrong identity rejection, restart, and no direct bypass. Endpoint and ABI selection MUST support a native Mac ARM emulator and Linux CI.

#### Scenario: Real Android traffic control

- **WHEN** a required instrumentation test skips or a bad identity reaches the direct sentinel
- **THEN** acceptance fails.

### Requirement: REQ-LAB-PEERS — Protocol peer execution

The lab MUST run existing independent Xray, AWG, SSH, Mieru, and AnyTLS peers and an independent Hysteria2 peer. Other local protocol contracts MUST identify their actual evidence tier.

#### Scenario: Protocol peer execution control

- **WHEN** a local protocol scenario runs
- **THEN** the result identifies the exact peer and tests executed without upgrading internal fixtures to independent interoperability.

### Requirement: REQ-LAB-ISOLATION — Evidence and isolation boundaries

Packet fidelity MUST be measured on a Linux path before NAT or TCP forwarding. Runtime isolation and offline claims MUST require observed egress controls. Artifacts MUST exclude secrets and raw captures from public output.

#### Scenario: Evidence and isolation boundaries control

- **WHEN** only a host mock, NAT capture, or imported profile succeeds
- **THEN** the report does not claim Android, packet fidelity, or external-provider acceptance.

### Requirement: REQ-LAB-AUTOMATION — Common entrypoint

Local commands and CI MUST consume the same manifest, report mandatory scenario results, and run cleanup after failures.

#### Scenario: Common entrypoint control

- **WHEN** a mandatory adapter times out or loses a fixture
- **THEN** the report names the failure and run-owned resources are cleaned.
