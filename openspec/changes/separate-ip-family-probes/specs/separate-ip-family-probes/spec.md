## Purpose

Expose independent IP destination path measurements without mixing address families or treating DNS64 discovery as connectivity.

## ADDED Requirements

### Requirement: REQ-IP-SEPARATE — Independent destination paths

The implementation MUST offer an explicit raw-path profile and full-analysis stage with separate bounded IPv4, IPv6 and NAT64 TCP probes to a paired reference service. A probe MUST use only its numeric destination family, without hostname or other-family fallback. TCP success MUST NOT be described as full Internet or HTTP availability.

#### Scenario: One family fails

- **WHEN** IPv4 connects and IPv6 times out
- **THEN** IPv4 remains reachable and IPv6 has a distinct timeout result

### Requirement: REQ-IP-NAT64 — Discovery and translation evidence

The implementation MUST discover DNS64 prefixes through captured network DNS servers using ipv4only.arpa AAAA queries and RFC 6052 prefix formats. It MUST separate prefix discovery from connection to a synthesized reference endpoint, reject ambiguous or malformed discovery, and avoid connecting to discovery sentinel addresses. Missing discovery MUST remain inconclusive. Proxy paths MUST NOT reuse a local NAT64 prefix.

#### Scenario: Prefix without translation

- **WHEN** a valid prefix is found but the synthesized TCP target cannot connect
- **THEN** the result records discovery and a failed connection separately

### Requirement: REQ-IP-PRESENT — Durable bounded evidence

The implementation MUST preserve family, stage, status, duration and stable failure reason through current results, history and explicit exports. UI text MUST exist in all ten locales and MUST state the measurement scope. Legacy results without evidence MUST remain unknown. Export MUST redact addresses and prefixes while retaining safe facts.

#### Scenario: Open a saved result

- **WHEN** a recorded probe is loaded from history
- **THEN** it shows the same validated evidence as the live result

### Requirement: REQ-IP-BOUNDS — Cancellation and scope

The implementation MUST bound network work and honor cancellation and the scan deadline. A network change MUST invalidate authority through the existing scan scope guard. Existing profiles without the optional configuration MUST NOT start these probes.

#### Scenario: Unsupported proxy path

- **WHEN** the configuration is sent on a proxy path
- **THEN** probes report unsupported scope without making underlay claims
