## Purpose

Process QUIC Initial data and active probes with bounded parsing and verified wire integrity.

## ADDED Requirements

### Requirement: REQ-QUIC-ACTIVE-HELLO — Build valid active probe ClientHello

Active Chrome, Firefox and generic probes MUST use a QUIC ClientHello with an empty legacy session ID, unique extensions, correct lengths, fresh random and valid key shares, and QUIC transport parameters bound to the packet source CID. Fixed desync templates MUST retain their separate role.

Active probes for literal IP targets MUST omit the server_name extension. DNS probes MUST retain the requested server name.

#### Scenario: Build valid active probe ClientHello

- **WHEN** a v1 or v2 active probe is built for a target
- **THEN** a strict independent QUIC TLS backend accepts its ClientHello and the packet decodes for that target

#### Scenario: Build an active probe for an IP target

- **WHEN** a v1 or v2 active probe is built for an IPv4 or IPv6 literal
- **THEN** an independent decoder finds no server_name extension, accepts the protected Initial and TLS handshake input, and reads transport parameters that match its source CID

### Requirement: REQ-QUIC-RESPONSE-INTEGRITY — Validate probe response integrity

The Kotlin probe MUST use the native Initial and Retry integrity validator. Reserved-version VN probes MUST check both echoed CIDs and a nonempty four-byte version list with no zero or offered version. Malformed responses MUST NOT produce QUIC_OK.

#### Scenario: Validate probe response integrity

- **WHEN** a header-only Initial, damaged tag or malformed VN is received from the requested address
- **THEN** the probe rejects it without claiming TLS or HTTP/3 success

### Requirement: REQ-QUIC-INITIAL-TOKEN — Preserve Initial tokens

The Initial builder MUST preserve a parsed token and include its bytes in packet lengths and authenticated header bytes.

#### Scenario: Preserve Initial tokens

- **WHEN** a v1 or v2 Initial with a nonempty token is parsed and packetized
- **THEN** an independent decoder reads the same token and ClientHello

### Requirement: REQ-QUIC-INITIAL-ACK — Read CRYPTO alongside acknowledgments

CRYPTO extraction MUST accept valid ACK and ACK_ECN frames in Initial packets and reject truncated or invalid acknowledgment ranges with bounded work.

#### Scenario: Read CRYPTO alongside acknowledgments

- **WHEN** a complete ClientHello shares an Initial payload with ACK or ACK_ECN
- **THEN** the same hostname is extracted without treating the ACK bytes as CRYPTO

### Requirement: REQ-QUIC-DATAGRAM-CRYPTO — Collect coalesced Initial CRYPTO

The helper MUST combine compatible Initial CRYPTO fragments within one UDP datagram, preserve marker positions, and reject mixed Initial connection or version contexts. It MUST NOT retain reassembly state across datagrams.

#### Scenario: Collect coalesced Initial CRYPTO

- **WHEN** two compatible Initial packets contain contiguous parts of one ClientHello in one datagram
- **THEN** the helper returns the full ClientHello and correct packet-relative markers

### Requirement: REQ-QUIC-HELLO-COMPLETE — Distinguish a complete ClientHello

Full Initial parse, layout and seed APIs MUST reject a ClientHello prefix that does not satisfy its declared handshake and nested vector lengths. This repair MUST preserve the general TLS parser behavior supplied by the published integration base.

#### Scenario: Distinguish a complete ClientHello

- **WHEN** a CRYPTO frame contains SNI but only 100 of 241 declared handshake bytes
- **THEN** the helper does not return a complete ClientHello or rebuild seed

### Requirement: REQ-QUIC-PROBE-CANCEL — Cancel active UDP probe work

Cancelling a probe MUST close its active socket and promptly release the caller. Resolution and exchange MUST share a bounded deadline. On older Android versions a running system DNS lookup may finish later, but the DNS worker count MUST remain bounded.

#### Scenario: Cancel active UDP probe work

- **WHEN** a probe waits on a loopback peer or a blocked resolver and the caller cancels
- **THEN** the caller returns before the I/O timeout and no active probe socket remains
