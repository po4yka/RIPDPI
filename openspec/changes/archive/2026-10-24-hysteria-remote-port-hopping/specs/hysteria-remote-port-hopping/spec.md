## Purpose

Preserve Hysteria2 port hopping from import through native runtime and keep authenticated TCP and UDP payload usable while local and remote UDP ports change.

## ADDED Requirements

### Requirement: REQ-HYH-IMPORT — Normalize imported hopping policy

The implementation MUST normalize URI, sing-box, Clash and bundle hopping fields to one model. It MUST reject malformed or conflicting sources. Ports MUST be in 1..65535. Exact millisecond intervals MUST be in 5000..9223372036854 with a 30000 ms default. Equal bounds MUST mean fixed cadence; reversed bounds and finer precision MUST fail admission.

#### Scenario: Canonical port set and interval sources

- **WHEN** URI multi-port authority, sing-box server_ports/hop_interval/hop_interval_max, documented Clash ports/hop-interval or existing bundle extras supply policy
- **THEN** normalization sorts and merges the port union, rejects empty or reversed ranges, preserves exact durations, and rejects conflicting duplicates or intervals without ports

#### Scenario: Standard imports preserve a union and random window

- **WHEN** a valid profile supplies multiple individual ports and ranges with a valid lower and upper interval
- **THEN** the selectable profile contains the canonical union and the exact interval bounds

#### Scenario: Invalid or conflicting hopping policy

- **WHEN** an import supplies a malformed range, an unsupported precision, an out-of-bound interval, intervals without ports, or disagreeing standard and extension values
- **THEN** it fails admission with a fixed non-secret reason and does not create a scalar-port replacement

### Requirement: REQ-HYH-WIRE — Preserve policy across storage and activation

The implementation MUST preserve policy across persistence, backup, activation, chain entry and native JSON. Flat and chain JSON MUST use optional hysteriaPortHopPorts, hysteriaPortHopIntervalMillis and hysteriaPortHopIntervalMaxMillis. Ports MUST use canonical comma union with hyphen ranges. Rust MUST validate the policy and monotonic deadline before any DNS, protection callback or network I/O.

#### Scenario: Convert stored dormant policy once

- **WHEN** storage loads old portHopPorts and portHopInterval strings
- **THEN** it converts them once to the validated model and rejects invalid data without a scalar-port fallback or a second runtime model

#### Scenario: Stored profile activates through a chain entry

- **WHEN** a hopping profile is saved, reloaded and used directly or as a supported chain entry
- **THEN** the native configuration receives the same canonical port union and millisecond bounds

#### Scenario: Invalid native JSON

- **WHEN** native JSON has invalid hopping fields or a deadline that the platform cannot represent
- **THEN** native construction fails before DNS, socket protection or outbound traffic

### Requirement: REQ-HYH-RUNTIME — Rotate ports without replacing the session

The native implementation MUST choose an initial configured remote port and rotate local and remote ports on the interval policy. It MUST retain one authenticated QUIC connection, the prior receive socket through the next hop, configured Salamander, and source-address validation. Existing source migration MUST preserve the hopping adapter and schedule.

#### Scenario: Scheduled rotation and remote filtering

- **WHEN** a fixed or independently sampled random interval expires
- **THEN** a new protected local socket sends to a different configured remote port when more than one exists, while received sources must match the resolved server IP and port union before normalization to the logical QUIC endpoint

#### Scenario: Continuous authenticated traffic

- **WHEN** TCP and UDP payload cross at least three scheduled hops through a real upstream peer on IPv4 and IPv6
- **THEN** independent receipts show changed local and remote ports, byte-correct payload and no new authentication or replacement application session

#### Scenario: Delayed packets on the previous path

- **WHEN** a valid response arrives on the prior socket after a hop
- **THEN** the same QUIC connection can receive the packet during the overlap period

### Requirement: REQ-HYH-LIFECYCLE — Protect and release each socket

Every non-loopback outbound socket created while VPN protection is active MUST pass the configured protection policy before outbound use. Protection failure MUST close the new socket and propagate failure without unprotected traffic. Stopping, cancelling or dropping the session MUST stop its schedule and release current and prior sockets and pending receive tasks. Hopping MUST not expose authentication, obfuscation secrets or endpoint identifiers in telemetry or error text.

#### Scenario: Replacement socket protection fails

- **WHEN** protection rejects a replacement socket during an active hopping session
- **THEN** no packet uses that socket and the failure is observable through the session error path

#### Scenario: Stop and restart

- **WHEN** the last session owner stops or is dropped and a later session starts
- **THEN** the old timer and sockets are released and the new session owns an independent schedule

### Requirement: REQ-HYH-PEER — Prove real protocol acceptance

Acceptance MUST use the existing pinned independent Hysteria peer and real UDP forwarding endpoints with receipt counters. It MUST verify TCP and UDP continuity across multiple scheduled remote ports with and without Salamander, IPv4 and IPv6, negative protection, TLS/auth preservation, and deterministic resource cleanup. A local source-port rebind, mock encoder, interval sample or selectable profile alone MUST NOT count as positive hopping acceptance.

#### Scenario: Receipt-backed acceptance

- **WHEN** the controlled peer scenario finishes
- **THEN** its evidence identifies the tested revision, peer pin, address families, obfuscation cases, observed remote ports, payload outcomes and cleanup result separately from device or production acceptance
