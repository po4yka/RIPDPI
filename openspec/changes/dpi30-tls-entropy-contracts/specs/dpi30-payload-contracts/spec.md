## Purpose

Preserve TLS and payload contracts during explicit DPI-30 transformations.

## ADDED Requirements

### Requirement: REQ-DPI30-REAL — Preserve real payload

The runtime MUST pad only fake bytes and preserve genuine payloads in normal, adaptive, inactive and fallback paths.

#### Scenario: Preserve real payload

- **WHEN** entropy shaping is configured
- **THEN** real bytes contain no entropy prefix

### Requirement: REQ-DPI30-JOINT — Meet joint targets

Combined padding MUST satisfy both metrics on final bytes or return empty when no solution is found within budget.

#### Scenario: Meet joint targets

- **WHEN** AA repeated 200 times has targets 3.4 and 0.5 with budget 2048
- **THEN** both final metrics meet their targets

### Requirement: REQ-DPI30-BUDGET — Respect byte budget

Every mode MUST respect the byte budget, including zero.

#### Scenario: Respect byte budget

- **WHEN** the maximum padding is zero
- **THEN** no padding bytes are returned

### Requirement: REQ-DPI30-COMPOSE — Retain TLS composition

TLS minor mutation MUST preserve prelude processing of already fragmented ClientHello messages.

#### Scenario: Retain TLS composition

- **WHEN** a fragmented ClientHello uses a different requested minor
- **THEN** TLSRec and TLSRandRec still process it

### Requirement: REQ-DPI30-SNI — Avoid SNI coupling

A valid ClientHello without SNI MUST support minor override without relaxing malformed input guards.

#### Scenario: Avoid SNI coupling

- **WHEN** a ClientHello has no server_name extension
- **THEN** the requested record byte changes

### Requirement: REQ-DPI30-DOCS — Describe wire semantics

Documentation MUST distinguish record minor mutation from negotiated TLS and use set bits per byte for popcount.

#### Scenario: Describe wire semantics

- **WHEN** minor4 or target3400 is configured
- **THEN** no TLS1.3 negotiation or 3.4 percent claim is made

