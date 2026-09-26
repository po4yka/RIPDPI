## Purpose

Native Rust contracts must reject invalid input, preserve access controls, and return accurate diagnostics and transport values across all supported entry points.

## ADDED Requirements

### Requirement: REQ-CONFIG-BOUNDS — Validate configuration bounds

Native configuration parsers MUST reject nonfinite or out-of-range times, invalid host filters and prefix widths, reversed port ranges, unsupported strategy versions, invalid window scales, and unsupported encrypted DNS protocols.

#### Scenario: Invalid explicit value

- **WHEN** a caller supplies an invalid explicit configuration value
- **THEN** parsing fails with an error that identifies the invalid field

### Requirement: REQ-PROXY-AUTH — Protect public listeners

A public proxy listener MUST require supported authentication on every entry point and MUST reject protocols that cannot provide that authentication.

#### Scenario: Protected listener receives SOCKS4

- **WHEN** SOCKS4 connects to a token-protected proxy listener
- **THEN** the connection is rejected before forwarding traffic

### Requirement: REQ-HTTP-FRAMING — Respect HTTP response framing

The HTTP diagnostic reader MUST parse content length from headers, reject incomplete bodies and ambiguous framing, and treat bodyless responses as bodyless.

#### Scenario: Truncated content-length body

- **WHEN** an HTTP response ends before its declared body length
- **THEN** the diagnostic reader reports an error

### Requirement: REQ-STRATEGY-RELOAD — Observe referenced host lists

The strategy reloader MUST return updated configuration when a referenced host list changes.

#### Scenario: Host list changed without main file edit

- **WHEN** a referenced host list changes while the main config file stays unchanged
- **THEN** the next reload observes the new list

### Requirement: REQ-TELEMETRY-GAUGE — Return gauge values

Telemetry snapshots MUST expose finite numeric gauge values rather than their integer bit patterns. Recorder installation MUST report whether its global registration succeeded.

#### Scenario: Numeric gauge snapshot

- **WHEN** a gauge records a finite value
- **THEN** the snapshot returns that numeric value

### Requirement: REQ-TELEGRAM-DC — Classify known Telegram endpoints

The WebSocket transport classifier MUST return the documented Telegram DC for known production and test bootstrap addresses.

#### Scenario: Known production endpoint

- **WHEN** the classifier receives a known production bootstrap address
- **THEN** it returns that endpoint's documented DC
