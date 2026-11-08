# socks5-client-contracts Specification

## Purpose
Define strict, bounded SOCKS5 client exchanges and valid UDP association ownership across application callers.

## Requirements

### Requirement: REQ-LOC09-HEADER — Validate reply headers

A SOCKS5 client MUST reject a reply with VER other than 05 or a nonzero reserved byte before it uses a relay or passes payload. It MUST preserve nonzero REP as a connection failure.

#### Scenario: Malformed success reply

- **WHEN** the server returns REP 00 with an invalid VER or RSV
- **THEN** setup fails without payload transmission.

### Requirement: REQ-LOC09-LIFETIME — Close stale TUN UDP bindings

A TUN UDP client MUST stop sending and delivering datagrams through an association after it observes control EOF, reset, or unexpected control data. Later traffic MUST use a new association.

#### Scenario: Control closes while UDP remains open

- **WHEN** control TCP ends while the old relay can still exchange UDP
- **THEN** old-binding traffic is rejected and later traffic can establish a fresh binding.

### Requirement: REQ-LOC09-DEADLINE — Bound upstream SOCKS setup

The upstream TCP client MUST use one connect budget through socket connection, method negotiation, authentication, and CONNECT reply. Shutdown MUST still interrupt pending setup.

#### Scenario: Slow reply

- **WHEN** reply bytes arrive individually within each read timeout but exceed the setup budget
- **THEN** setup fails within the total budget and no payload is sent.

### Requirement: REQ-LOC09-IPV6 — Keep proxy host identity

DNS HTTP transport MUST construct a valid SOCKS URI for an IPv6 literal while preserving the same raw host contract used by the TCP adapter.

#### Scenario: IPv6 proxy

- **WHEN** the SOCKS proxy host is ::1
- **THEN** DoH and ODoH client construction succeeds without changing the stored host.

### Requirement: REQ-LOC09-DOMAIN — Handle domain UDP relay endpoints

UDP clients MUST handle domain relay endpoints within their existing resolver policy and setup budget. TUN and Kotlin probes MUST accept localhost and IP-literal domain fields without DNS and reject other names. Diagnostics MUST use its existing bounded resolver. Unused numeric-only helpers retain their scope.

#### Scenario: Domain relay endpoint

- **WHEN** ASSOCIATE returns a domain relay endpoint
- **THEN** the client reaches that relay or returns a bounded resolution error without bypassing the configured resolver policy.

### Requirement: REQ-LOC09-CREDENTIALS — Validate credential bytes

Repository-owned SOCKS client boundaries MUST reject username or password lengths outside 1..255 UTF-8 bytes before they send authentication data. Configured owned-listener credentials MUST NOT permit a NO-AUTH downgrade.

#### Scenario: Invalid credentials or downgrade

- **WHEN** a field is empty or oversized, or a server selects NO-AUTH for configured credentials
- **THEN** the client rejects setup before CONNECT or payload output.

### Requirement: REQ-LOC09-COMPAT — Preserve valid client traffic

Clients MUST preserve valid IPv4, IPv6, domain, partial-read, REP-error, half-close, socket-protection, and cancellation behavior. Unsupported BIND, GSSAPI, and DoQ-over-SOCKS modes MUST retain their explicit scope.

#### Scenario: Fragmented valid reply

- **WHEN** a valid reply arrives in separate bytes followed by payload
- **THEN** the reply consumes only its own bytes and the payload is preserved.

### Requirement: REQ-LOC09-ENDPOINT-AUTH — Reject conflicting upstream credentials

Runtime startup MUST reject groups that configure different authentication policies for the same upstream socket address, including a mixture of credentials and NoAuth. The error MUST NOT contain credential values.

#### Scenario: Conflicting endpoint credentials

- **WHEN** two groups assign unequal optional credential pairs to one upstream address
- **THEN** configuration fails before bind rather than selecting the first pair.
