## Purpose

Use valid TLS names and negotiated application protocols in existing native client paths without changing trust or socket protection.

## ADDED Requirements

### Requirement: REQ-STD02-DOT — Apply DoT ALPN

The DoT client MUST offer dot after the selected builder factory. It SHALL accept dot or a legacy peer without ALPN and reject any other selected protocol before DNS bytes. Certificate checks and pooled stream ownership SHALL stay intact.

#### Scenario: Registered DoT protocol

- **GIVEN** a trusted DoT peer with dot ALPN
- **WHEN** a direct or SOCKS-backed resolver connects with the default or custom builder
- **THEN** the peer selects dot and receives a valid DNS frame

#### Scenario: Legacy and incompatible peers

- **WHEN** a legacy DoT peer omits ALPN
- **THEN** the verified connection may exchange DNS frames
- **WHEN** an HTTP-only peer cannot select dot
- **THEN** the client sends no DNS application bytes

### Requirement: REQ-STD02-H2 — Require H2 on HTTPS carriers

MASQUE H2 TCP and UDP and xHTTP SHALL offer h2 and require selected h2 before application bytes. The REALITY xHTTP carrier SHALL use this policy after its existing authentication. Direct raw REALITY SHALL retain its current protocol policy.

#### Scenario: Reject HTTP1 or absent selection

- **WHEN** a carrier peer selects HTTP1 or omits ALPN
- **THEN** the client refuses the carrier before H2 preface, CONNECT headers or proxy credentials

#### Scenario: H2 carrier

- **WHEN** a trusted carrier peer selects h2
- **THEN** the existing H2 data path remains available

### Requirement: REQ-STD02-NAME — Validate TLS identity

TLS clients SHALL use valid ASCII DNS names or IP identities. They SHALL remove an accepted DNS root dot, keep IP literals out of SNI, and reject invalid ASCII DNS labels and raw Unicode overrides. URL-derived Unicode domains SHALL retain the URL parser's existing IDNA conversion. URL authority brackets SHALL remain separate from TLS identity. Fake SNI SHALL NOT replace the certificate verification identity.

#### Scenario: Names and limits

- **WHEN** an input is a valid A-label, ASCII DNS name or IP identity
- **THEN** the TLS client uses that identity and validates its certificate
- **WHEN** an input contains NUL, whitespace, invalid labels or a raw Unicode override
- **THEN** validation fails before malformed SNI is sent

#### Scenario: Absolute names and IPv6 URLs

- **WHEN** a DNS name has one root dot or an HTTPS URL has an IPv6 literal
- **THEN** TLS uses the canonical DNS name or bare IP while HTTP authority retains required brackets

### Requirement: REQ-STD02-DIAGNOSTIC — Keep explicit diagnostic identity

A nonempty diagnostic TLS override SHALL be validated or refused. It SHALL NOT silently fall back to the target identity. Empty input SHALL retain the existing target fallback.

#### Scenario: Invalid explicit override

- **WHEN** a probe receives a nonempty invalid TLS name and a valid target
- **THEN** it reports an input error rather than a successful probe for the target name

#### Scenario: Empty override

- **WHEN** the TLS override is empty
- **THEN** the probe uses the target identity
