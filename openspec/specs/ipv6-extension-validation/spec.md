# ipv6-extension-validation Specification

## Purpose
Reject invalid IPv6 extension inputs before they produce malformed or silently different packet behavior.

## Requirements

### Requirement: REQ-IPV6-ROUTING — Reject unsupported routing

The implementation MUST reject configured routing and MUST NOT emit the malformed routing packet from the public transform.

#### Scenario: Routing selected

- **WHEN** routing is selected
- **THEN** loading fails and the direct transform returns no packet.

### Requirement: REQ-IPV6-TYPE — Validate explicit types

The implementation MUST reject unknown or empty explicit ext_type and preserve supported aliases and absent-value defaults.

#### Scenario: Invalid type

- **WHEN** YAML or TOML supplies an invalid ext_type
- **THEN** loading returns an error rather than a replacement method.

### Requirement: REQ-IPV6-UDP — Validate applicability

The implementation MUST reject non-none IPv6 profiles on UDP steps other than ipfrag2_udp and preserve the five supported profiles on valid ipfrag2_udp.

#### Scenario: Wrong UDP step

- **WHEN** fake_burst specifies hopByHop
- **THEN** runtime conversion returns an error.
