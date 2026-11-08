## Purpose

Keep the audited TLS consumers encrypted, authenticated and bounded according to their actual protocol contracts.

## ADDED Requirements

### Requirement: REQ-STD01-F01 — TLS intent on every port

A diagnostic with an explicit TLS name MUST perform a TLS handshake on every port, independent of certificate-verification policy. An explicit plain transport MUST remain plain.

#### Scenario: TLS intent on every port

- **WHEN** an explicit TLS diagnostic uses a nonstandard port and a diagnostic custom verifier
- **THEN** a plaintext peer cannot produce a successful TLS diagnostic and a TLS peer receives ClientHello.

### Requirement: REQ-STD01-F02 — DoT pin enforcement

The pinned DoT verifier MUST be evaluated for every peer. A successful default CA check MUST NOT bypass the configured pin. Pin-only verification MUST retain proof of possession of the leaf private key.

#### Scenario: DoT pin enforcement

- **WHEN** a trusted peer presents a certificate that does not match the configured pin
- **THEN** the handshake fails before DNS application bytes.

### Requirement: REQ-STD01-F03 — DoT application protocol

DoT MUST offer only its DNS application protocol and MUST reject an incompatible negotiated ALPN before DNS bytes. A legacy DoT server without ALPN MUST remain usable.

#### Scenario: DoT application protocol

- **WHEN** a DoT server negotiates dot or omits ALPN
- **THEN** DNS exchange succeeds; an incompatible selected protocol receives no DNS payload.

### Requirement: REQ-STD01-F04 — H2 application protocol

MASQUE H2 and native xHTTP MUST require negotiated h2 before the HTTP/2 connection preface, including the REALITY carrier.

#### Scenario: H2 application protocol

- **WHEN** a TLS peer selects http/1.1 or omits ALPN
- **THEN** the consumer returns an ALPN error before any HTTP/2 application bytes.

### Requirement: REQ-STD01-F05 — DoQ query isolation

A timed-out or cancelled DoQ transaction MUST cancel only its own streams with the protocol cancellation code. It MUST NOT close a healthy shared connection.

#### Scenario: DoQ query isolation

- **WHEN** one concurrent query times out while another remains within its own deadline
- **THEN** the second query succeeds and a later query can reuse the connection.

### Requirement: REQ-STD01-F06 — Bounded TLS diagnostic workers

After its bounded protected TCP connect, every connection-concurrency TLS worker MUST stop its TLS handshake on the earliest cell or scan deadline or scan cancellation. Joining workers MUST NOT bypass those bounds. Administrative cancellation MUST NOT become evidence of network blocking.

#### Scenario: Bounded TLS diagnostic workers

- **WHEN** a peer is silent or sends partial TLS records below the per-read timeout and the scan is cancelled or expires
- **THEN** the TLS handshake closes its socket and the cell returns within the polling tolerance without a network-blocking verdict.

### Requirement: REQ-STD01-F07 — Explicit unsupported TUIC early data

The current TUIC provider MUST reject zero_rtt=true as unsupported before network activity until a replay-safe resumed implementation exists. It MUST NOT silently treat that setting as active.

#### Scenario: Explicit unsupported TUIC early data

- **WHEN** a caller supplies a TUIC profile with zero_rtt=true
- **THEN** validation returns an explicit unsupported error before resolution or connection; zero_rtt=false retains the normal path.

