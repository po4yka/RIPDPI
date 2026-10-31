## ADDED Requirements

### Requirement: REQ-OUT-1793433244049580-001 — Documented UDP wire semantics

The repository MUST record the Mieru UDP carrier semantics transcribed from a pinned upstream release before any implementation relies on them.

#### Scenario: Semantics are traceable

- **WHEN** the UDP carrier is reviewed
- **THEN** `PROTOCOL.md` names the upstream tag and commit and defines the payload nonce order, acknowledgement meaning, retransmission timing, window units, fragment ordering and reassembly, and session keying and idle timeouts

### Requirement: REQ-OUT-1793433244049580-002 — Reliable UDP carrier

A Mieru profile with `protocol=udp` MUST carry relay streams over a protected UDP socket with reliable, ordered delivery, and MUST fail closed on authentication or decryption errors.

#### Scenario: Lossy loopback transfer

- **WHEN** a 1 MiB stream crosses a loopback UDP path that drops and reorders packets
- **THEN** the receiver gets the exact bytes in order and every carrier socket was protected under the runtime's SocketProtectionPolicy

#### Scenario: Tampered packet

- **WHEN** a UDP segment fails AEAD verification
- **THEN** the carrier drops it without delivering data and does not reuse a nonce

### Requirement: REQ-OUT-1793433244049580-003 — Upstream interoperability

The UDP carrier MUST interoperate with the pinned upstream Mieru server before Mieru is reported as UDP-capable.

#### Scenario: Pinned upstream server

- **WHEN** the outbound interop runner executes the `udp_*` Mieru cases against the pinned upstream server
- **THEN** every case passes, and only then do the Mieru capability registry rows report UDP support
