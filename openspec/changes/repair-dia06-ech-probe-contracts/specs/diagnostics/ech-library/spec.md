## Purpose

Keep ECH library measurements distinct from unavailable inputs and preserve the actual network path and authenticated TLS evidence.

## ADDED Requirements

### Requirement: REQ-DIA06-NODATA — Preserve missing HTTPS data

The driver MUST return no ECH config for a clean HTTPS NODATA answer. NXDOMAIN and transport errors MUST remain distinct errors.

#### Scenario: No HTTPS records

- **WHEN** a DNS server returns NOERROR without HTTPS answers
- **THEN** the runner reports NoEchConfig, not a resolver setup failure.

### Requirement: REQ-DIA06-CONTEXT — Report actual path provenance

The runner MUST reject unsupported resolver, relay or strategy context before network I/O. It MUST NOT copy configured relay hints into observed provenance.

#### Scenario: Unsupported active path

- **WHEN** any resolver, relay or strategy hint is present
- **THEN** the runner returns Inconclusive with no used relay and performs no lookup or handshake.

### Requirement: REQ-DIA06-REJECTION — Preserve authenticated rejection

The driver MUST preserve authenticated ECH rejection with absent, empty or unusable retry configurations. It MUST verify the public name before retry, use at most one fresh connection to the original peer, and MUST NOT report outer TLS or plaintext fallback as ECH success.

#### Scenario: Rejection without usable retry configuration

- **WHEN** a verified outer handshake rejects ECH without a compatible retry config
- **THEN** the result identifies ECH rejection, not a generic transport failure.

### Requirement: REQ-DIA06-ADMISSION — Exclude unusable HTTPS records

Discovery MUST ignore AliasMode parameters and ServiceMode records whose mandatory parameters it cannot use. It MUST NOT use ECH from an incompatible record or a ServiceMode record in an AliasMode RRset.

#### Scenario: Incompatible service record

- **WHEN** a record has an unsupported mandatory parameter or AliasMode parameters
- **THEN** it does not supply the selected ECH config.

### Requirement: REQ-DIA06-UNCERTAINTY — Preserve transport uncertainty

The probe MUST report Inconclusive for a transport timeout or transient I/O without authenticated TLS evidence. Typed TLS and certificate failures MUST remain distinct from transport uncertainty.

#### Scenario: Silent TLS peer

- **WHEN** a TCP peer sends no TLS response before the deadline
- **THEN** the result is Inconclusive and the socket is closed.

### Requirement: REQ-DIA06-DEADLINE — Bound complete DNS discovery

The driver MUST apply one wall-clock deadline to DNS discovery including retries. Invalid durations MUST fail without panic. Dropping the operation MUST release owned resources.

#### Scenario: Silent DNS peer

- **WHEN** the resolver retries against a silent server
- **THEN** the complete operation stops at its deadline with bounded scheduler tolerance.
