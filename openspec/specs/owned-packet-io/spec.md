# owned-packet-io Specification

## Purpose
Carry raw IP packets through an already-owned platform descriptor without interface creation, ioctl probes, or packet-header changes.

## Requirements

### Requirement: REQ-PACKET-BYTES — Preserve packet data

The adapter MUST read and write one packet at a time without adding or removing headers.

#### Scenario: Raw packet round trip

- **WHEN** a platform packet descriptor or socketpair supplies raw IP packet bytes
- **THEN** the packet loop receives the same bytes and returns response packets without a packet-information header.

### Requirement: REQ-PACKET-READINESS — Use nonblocking readiness

The adapter MUST preserve descriptor flags, enable nonblocking I/O, and clear cached readiness only after an I/O operation returns WouldBlock. Readiness waits MUST be cancel-safe.

#### Scenario: Drain then wait for the next packet

- **WHEN** the loop drains the input and a later packet arrives
- **THEN** the empty read reports WouldBlock and the next readiness wait permits the new packet to be read without blocking the runtime.

### Requirement: REQ-PACKET-OWNERSHIP — Retain one descriptor owner

The adapter MUST own the supplied descriptor through setup, packet I/O, errors, and cancellation. It MUST close the descriptor when that ownership ends. It MUST NOT log packet data or descriptor identity.

#### Scenario: Stop the tunnel

- **WHEN** tunnel setup fails or the tunnel stops after cancellation
- **THEN** the supplied descriptor is closed and the peer observes that the packet endpoint is gone.

### Requirement: REQ-PACKET-READY-BARRIER — Preserve runtime contracts

The tunnel MUST keep the existing run_tunnel and Android JNI contracts. It MUST invoke runtime readiness only after fallible setup and before the first packet read.

#### Scenario: Setup failure

- **WHEN** packet-loop setup fails
- **THEN** the error is returned and the readiness callback is not invoked.
