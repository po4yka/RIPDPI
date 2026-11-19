## Purpose

Bound the hop distance of a middlebox that reacts to a test SNI, using TTL-limited ClientHello attempts that need no root and no ICMP capture.

## ADDED Requirements

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-CAPABILITY — Capability

The probe MUST run only on a protected socket bound to the selected direct network after the TTL setter succeeds. If any of these fails, the run MUST be `unsupported`, MUST NOT ask for root, and MUST NOT report hops.

#### Scenario: TTL setter fails

- **WHEN** setting the outbound TTL on the connected socket returns an error
- **THEN** the run is `unsupported` and other diagnostics in the same scan still complete

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-PAIR — Paired TTL ladder

The probe MUST complete the TCP handshake with the default TTL, then send one ClientHello at TTL k on a fresh socket, for control and test SNI alternately, for ordered k up to 32, with two repetitions per cell and one total deadline. It MUST probe only the user-selected endpoint.

#### Scenario: Test SNI reacts before the server

- **WHEN** the test SNI receives an injected RST at k = 5, both SNIs are silent at k = 4, the control is silent at k = 5, and the control receives a ServerHello at k = 9
- **THEN** the result is a divergence interval of hops 5 to 5 with path length 9, labeled as an observation and not as an identified device

#### Scenario: Reaction only at the server

- **WHEN** the test SNI first reacts at the same k at which the control receives a ServerHello
- **THEN** the result is inconclusive

#### Scenario: Cancellation

- **WHEN** the user cancels during the ladder
- **THEN** the probe closes the current socket, stops, and keeps the attempts already recorded

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-PRIVACY — Privacy

The ordinary diagnostic export MUST contain only the interval, the path length, the limits, and the endpoint label. It MUST NOT contain per-attempt timings.

#### Scenario: Ordinary export

- **WHEN** a report with a completed ladder is exported
- **THEN** the archive contains no per-attempt timing vector

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-LIMITS — Limits

The result MUST list each detected limit (control never reaches the server, control reacts like the test, ServerHello at k of 1 or 2, network change, inconsistent repetitions) and MUST NOT state that no interference exists.

#### Scenario: Network change

- **WHEN** the selected network changes during the ladder
- **THEN** the interval is withdrawn, the attempts stay visible, and the limit names the network change
