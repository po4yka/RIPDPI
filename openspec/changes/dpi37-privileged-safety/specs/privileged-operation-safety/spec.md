## Purpose

Preserve input integrity, bounded IPC delivery and terminal socket cleanup for privileged operations.

## ADDED Requirements

### Requirement: REQ-PRIV-REPLY — Bounded complete ICMP receive replies

The helper and client MUST transport every supported ICMP receive payload through a command-specific bounded response. Other control messages MUST retain the existing 8192-byte limit. A client MUST refuse a legacy peer without this receive-response support before command delivery.

#### Scenario: Maximum receive payload

- **WHEN** an authenticated receive returns 65493 payload bytes, including high-valued bytes
- **THEN** the response round trip preserves all bytes within the command response bound and total deadline.

#### Scenario: Unsupported peer and oversized unrelated reply

- **WHEN** a peer lacks the receive-response advertisement or another command returns more than 8192 bytes
- **THEN** the client refuses before receive delivery or rejects the unrelated oversized reply, and closes received descriptors.

### Requirement: REQ-PRIV-CHECKSUM — ICMPv4 input integrity

The receiver MUST reject an ICMPv4 message with an invalid checksum before envelope decoding and MUST continue its bounded receive loop.

#### Scenario: Independently specified corrupt data

- **WHEN** a valid odd- or even-length fixed message has its header, checksum or body corrupted
- **THEN** the parser rejects the corrupted message and accepts the original bytes with the expected payload.

### Requirement: REQ-PRIV-RESTORE — Terminal repair restoration errors

Both raw fake TCP and flagged TCP error exits MUST report failed repair restoration as terminal and retain the original operation error.

#### Scenario: Failure before emission and failed restoration

- **WHEN** the operation fails before emission and repair restoration also fails
- **THEN** the API returns a terminal error that includes both causes, without emitting or retrying real payload.
