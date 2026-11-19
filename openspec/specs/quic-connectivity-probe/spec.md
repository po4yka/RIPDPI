# quic-connectivity-probe Specification

## Purpose
Report what the scheduled QUIC v1 connectivity probe observed on its selected network path.

## Requirements

### Requirement: REQ-QUIC-PROBE-REPLY — Preserve the kind of correlated reply

The scheduled probe MUST separate Initial with CRYPTO, Initial with ACK, another valid Initial, Initial with transport close, Retry, and Version Negotiation. It MUST report HTTP/3 validation as false.

#### Scenario: The server rejects the offered ALPN

- **WHEN** the server returns a correlated Initial with a transport CONNECTION_CLOSE
- **THEN** the result records the close as an observed reply and does not report QUIC usability

### Requirement: REQ-QUIC-PROBE-AUTHORITY — Keep response evidence scoped to the path

The report MUST distinguish failure to receive a valid reply, setup failure, skip, cancellation, and budget exhaustion. The scheduled probe MUST leave direct capability and blocking recommendations to independent evidence.

#### Scenario: A proxy replies on a path whose direct capability is unknown

- **WHEN** an IN_PATH scan receives a QUIC reply or a RAW_PATH scan skips the target
- **THEN** the probe does not set or clear stored direct QUIC and UDP capabilities

### Requirement: REQ-QUIC-PROBE-DATAGRAM — Receive the complete UDP datagram

Direct and route-experiment sockets MUST pass the entire permitted UDP datagram to the validator.

#### Scenario: An Initial datagram is larger than the old buffer

- **WHEN** the selected UDP peer replies with 3000 bytes
- **THEN** the validator receives all 3000 bytes

### Requirement: REQ-QUIC-PROBE-FRAMES — Validate the first-flight payload

A correlated Initial MUST pass header and packet integrity checks, contain only complete permitted frames, and meet the minimum datagram size when it contains an ack-eliciting Initial.

#### Scenario: A protected Initial contains an incomplete frame

- **WHEN** an Initial decrypts but has an invalid frame sequence
- **THEN** the probe does not report a valid Initial

### Requirement: REQ-QUIC-PROBE-CANCEL — Bound and stop exchanges

The probe MUST check cancellation and deadline across transports, stop route attempts after either, and keep route counts bounded.

#### Scenario: A scan is cancelled during receive

- **WHEN** the operator cancels after the first Initial was sent
- **THEN** the current exchange stops without another send and the report distinguishes cancellation

### Requirement: REQ-QUIC-PROBE-PN — Use fresh packet numbers

A repeat in one Initial packet number space MUST use a new packet number. Independent exchanges MUST use fresh connection IDs.

#### Scenario: The first receive window expires

- **WHEN** plain Direct repeats the probe on the same socket
- **THEN** the new packet has the original CRYPTO data and connection IDs with a different packet number

### Requirement: REQ-QUIC-PROBE-SOCKS — Follow the SOCKS association

The UDP exchange MUST ignore unrelated or unsupported relay frames within the deadline, stop when the TCP association closes, and follow the VPN socket protection contract.

#### Scenario: The relay sends an unrelated UDP frame

- **WHEN** a frame with an unrelated source precedes a valid reply
- **THEN** the probe can observe the valid reply in the same receive window

### Requirement: REQ-QUIC-PROBE-TARGET — Validate the target before sending

The Initial MUST use a valid ASCII DNS server name or omit SNI for an IP literal. The app MUST convert a valid internationalized DNS name to its ASCII form before native execution. An invalid name or zero port MUST produce an input error before network I/O.

#### Scenario: A catalog target uses an internationalized hostname

- **WHEN** the app prepares a valid Unicode DNS name with a trailing root dot
- **THEN** the SNI uses the ASCII name without the final dot and DNS keeps the absolute name
