## Purpose

Use one strict numeric-address contract across Kotlin and Rust validation and routing.

## ADDED Requirements

### Requirement: REQ-IP-LITERAL — Parse numeric addresses without DNS

The application MUST use library parsing for IPv4 and IPv6 literals without hostname resolution. It MUST reject malformed compression, legacy IPv4 forms, zones, ranges, wildcards, and non-ASCII numeric syntax. Caller-owned trimming MAY occur before validation. IPv4-mapped IPv6 MUST retain its IPv6 family.

#### Scenario: Reject malformed resolver candidates

- **WHEN** a candidate contains `:1::2`, `1::2:`, `127.1`, `01.2.3.4`, or a hostname
- **THEN** validation rejects it without a DNS query.

#### Scenario: Accept standard address forms

- **WHEN** an input is a dotted-decimal IPv4 or a valid compressed, expanded, or mixed IPv6 literal
- **THEN** validation accepts it and retains its address family.

### Requirement: REQ-IP-CIDR — Validate prefixes and distinguish hosts from networks

CIDR parsing MUST validate decimal prefixes against the address width. Network matching MUST use the parsed family and prefix, including zero and full-width prefixes. Interface addresses MUST retain host bits; network rules MUST clear host bits. Explicit malformed prefixes MUST fail instead of falling back to a default.

#### Scenario: Host and network use different projections

- **WHEN** the input is `192.0.2.129/24`
- **THEN** an interface keeps `192.0.2.129` and a network rule uses `192.0.2.0/24`.

#### Scenario: Reject invalid prefix

- **WHEN** a prefix is negative, exceeds 32 or 128, contains a sign, or is not decimal
- **THEN** parsing rejects the CIDR.

### Requirement: REQ-IP-WIRE — Preserve canonical routing representation

The implementation MUST retain the existing canonical wire representation for valid destination networks: dotted IPv4 and lowercase expanded IPv6 groups without leading group zeros. Existing serialized schemas and network fingerprint recipes MUST remain unchanged.

#### Scenario: Canonical IPv6 network

- **WHEN** a routing rule contains `2001:DB8::1234/64`
- **THEN** the canonical wire value is `2001:db8:0:0:0:0:0:0/64`.
