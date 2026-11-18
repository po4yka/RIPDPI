## Purpose

Provide bounded reproducible network comparison evidence without overstating censorship attribution or exposing private network identities.

## ADDED Requirements

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-CAPABILITY — Capability

The implementation MUST probe only when platform capabilities and the selected direct network are verified; unsupported ICMP capture or socket TTL operations return unsupported without root escalation or fabricated hops.

#### Scenario: Capability evidence

- **WHEN** a non-root Android device cannot observe the required responses
- **THEN** the run remains explicitly unsupported and ordinary diagnostics still work

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-PAIR — Pair

The implementation MUST use a bounded user-started control/test SNI experiment against the same user-authorized endpoint with ordered TTL values, fresh connections, repeated controls and a total cancellation deadline. Do not scan arbitrary address ranges.

#### Scenario: Pair evidence

- **WHEN** control and test differ with usable controls and stable path evidence
- **THEN** report an observed divergence interval with uncertainty, not an identified TSPU device or definitive censor location

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-PRIVACY — Privacy

The implementation MUST keep path addresses and timings local; redact raw hops, public IPs and precise timing from ordinary exports. Any diagnostic detail export requires a separate visible choice and disclosure.

#### Scenario: Privacy evidence

- **WHEN** the normal diagnostic archive is exported
- **THEN** it contains no raw route identifiers or detailed timing vector

### Requirement: REQ-TLS-INTERFERENCE-HOP-COMPARISON-LIMITS — Limits

The implementation MUST report missing ICMP, asymmetric paths, load balancing, invalid control, proxy interception and network changes as measurement limits, never proof of no censorship.

#### Scenario: Limits evidence

- **WHEN** a control fails or the network changes
- **THEN** inference is invalidated while bounded observations remain available

The TTL experiment MUST establish TCP normally, then apply the bounded outbound TTL to ClientHello transmission, not to the initial SYN. Retransmissions and socket reuse MUST remain isolated within the per-attempt budget. If the platform cannot enforce this lifecycle or observe the necessary responses, it MUST return unsupported or inconclusive without a hop-location inference.
