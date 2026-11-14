## Purpose

Preserve packet-send and socket-restoration failure boundaries across direct and helper runtimes.

## ADDED Requirements

### Requirement: REQ-RST-CLEANUP — Stop on uncertain socket restoration

The fake-RST sender MUST attempt queue and repair restoration. If restoration fails, every runtime caller MUST return a terminal uncertain-state error and MUST NOT write the original payload. A proven safe refusal MAY retain the plain-write fallback. The runtime MUST restore the temporary TTL before it returns the error.

#### Scenario: Repair restoration fails

- **WHEN** fake-RST cleanup cannot restore the TCP socket
- **THEN** the runtime reports an uncertain terminal failure and the original payload is not written

#### Scenario: Reset is safely refused

- **WHEN** a sender proves that socket state is safe after refusing the reset
- **THEN** the runtime retains the existing plain-write fallback

### Requirement: REQ-UDP-ERROR-PARITY — Preserve UDP send boundaries

Direct and helper UDP fragment errors MUST retain their error kind and send phase. Only an explicit pre-send InvalidInput error MAY trigger the existing plain-datagram fallback. Uncertain, legacy opaque and malformed errors MUST remain terminal. Error handling MUST NOT expose payload bytes in logs.

#### Scenario: Builder rejects a split before send

- **WHEN** the helper reports an explicit InvalidInput error before emission
- **THEN** the runtime sends exactly one original datagram through its existing fallback

#### Scenario: A send may have occurred

- **WHEN** the helper reports an uncertain fragment send or lacks valid phase metadata
- **THEN** the runtime does not repeat the datagram

### Requirement: REQ-FRAGMENT-MD5-SCOPE — Describe the implemented option scope

The documentation MUST NOT claim that the IP fragment builder emits a TCP MD5 option. No new packet capability is required by this repair.

#### Scenario: Read the MD5 section

- **WHEN** a user reads the raw TCP MD5 option scope
- **THEN** the description excludes the unsupported ipfrag2 combination
