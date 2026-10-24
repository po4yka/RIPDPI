## Purpose

Prove authenticated application payloads and server replacement through the real
Android VPN path while retaining explicit local and physical evidence boundaries.

## ADDED Requirements

### Requirement: REQ-ANDROID-P1-TLS — Exercise nginx TLS XHTTP

The acceptance lane MUST run P1 through a real nginx TLS frontend and the production
Android Xray provider, with explicit local trust and an independent destination receipt.
It MUST preserve the existing XHTTP over REALITY scenario as a distinct transport.

#### Scenario: Valid P1 path

- **WHEN** the Android client selects the owned nginx TLS XHTTP endpoint
- **THEN** separate-UID traffic crosses its TUN and the selected peer records the completed payload
- **AND** wrong client identity and wrong TLS trust fail without direct bypass

### Requirement: REQ-ANDROID-TRANSACTION — Verify complete traffic

The lane MUST check DNS, download, upload and bounded sustained traffic for REALITY,
nginx TLS XHTTP, Hysteria2 and AmneziaWG using real app service and native bindings.
Payload success MUST require byte counts and digest equality, not handshake or activity counters.

#### Scenario: Every selected protocol completes data

- **WHEN** a selected transport starts with valid owned credentials
- **THEN** a separate UID completes the required transactions and the destination records their identities
- **AND** truncation, corruption, missing receipts or a stopped tunnel cause failure

#### Scenario: Background DNS does not produce acceptance evidence

- **WHEN** Android sends a valid DNS query for a name outside the owned acceptance domain
- **THEN** the AWG peer returns DNS REFUSED without an upstream lookup or an acceptance receipt
- **AND** it records only a rejection count, not the queried name
- **AND** malformed queries and failures of the owned A/AAAA query remain errors
- **AND** the owned IPv4 and IPv6 probes still require fresh matching peer receipts

### Requirement: REQ-ANDROID-RECOVERY — Restore traffic after transport failure

The lane MUST exercise peer loss and recovery and automatic selection of a TCP
reserve after the active UDP transport fails, using observed application traffic.

#### Scenario: UDP failure selects an authenticated TCP reserve

- **WHEN** a routed fault blocks the active UDP transport after successful application traffic
- **THEN** the client applies the selected TCP reserve and completes a new verified transaction
- **AND** the failed endpoint cannot pass through a direct path or stale receipt

### Requirement: REQ-ANDROID-MIGRATION — Prove replacement-server traffic

The lane MUST exercise the production migration or refresh entry point and require
a fresh replacement-server receipt before treating migration as successful.

#### Scenario: Replacement succeeds before retirement

- **WHEN** the old endpoint remains unreachable and a valid replacement delivery is applied through the saved independent mirror
- **THEN** the client completes a transaction attributed to the replacement endpoint
- **AND** the old peer process is retired only after an authenticated replacement receipt and full-TUN transaction

#### Scenario: Invalid replacement cannot authorize retirement

- **WHEN** the replacement endpoint rejects its identity or cannot complete a payload
- **THEN** migration success is not reported and the saved reserve remains available

### Requirement: REQ-ANDROID-EVIDENCE — Keep execution and scope honest

The runner MUST reject missing tests, skipped checks, stale source or artifact identity,
incomplete transactions and uncertain cleanup. Sensitive raw output MUST remain private.
Local emulator acceptance MUST NOT establish physical sleep/wake, Wi-Fi/cellular
handover, filtered-client or public-provider acceptance.

#### Scenario: Missing runtime prerequisite

- **WHEN** the selected owned emulator or authorized physical device, real native artifact or independent peer is unavailable
- **THEN** the selected runtime scenario fails or reports blocked without a success receipt
- **AND** existing unrelated devices, processes, credentials and worktrees remain unchanged

#### Scenario: Local USB transport has a separate scope

- **WHEN** a selected physical device has no common LAN with the owned peer host
- **THEN** a named USB scenario can use a test-only datagram bridge while retaining real VPN, native protocol and independent payload checks
- **AND** its result marks direct device-to-LAN UDP, underlying-network bypass and Wi-Fi/cellular handover as unverified
- **AND** the direct LAN scenarios retain their original assertions

### Requirement: REQ-ANDROID-USB-FLOW — Preserve concurrent datagram flows

The USB test carrier MUST assign a stable flow identity to each Android loopback
source and preserve it in both directions. Each host flow MUST own a separate UDP
socket to the pinned peer. Frames MUST carry an explicit version, flow ID, and
bounded payload length. The carrier MUST authenticate before it allocates a flow,
reject malformed frames and unknown reply IDs, and bound flow count and lifetime.
The old length-only frame format MUST NOT remain supported.

#### Scenario: Concurrent QUIC sessions receive their own replies

- **WHEN** two native UDP sources send through one authenticated USB carrier and peer replies arrive in reverse order
- **THEN** each reply reaches only its original source with its original payload
- **AND** independent host sockets retain the peer's source-flow distinction

#### Scenario: Companion gate preserves the carrier budget

- **WHEN** the carrier opens all 64 allowed flows through its UDP fault gate
- **THEN** the gate admits every flow and existing flows continue to receive replies
- **AND** a 65th source fails without allocating another upstream socket

#### Scenario: Invalid or excessive flow

- **WHEN** a frame has an invalid version, ID, size, or exceeds the flow bound
- **THEN** the carrier fails without forwarding that frame
- **AND** cleanup closes all owned flow sockets and workers
- **AND** failed runs retain a fixed gate error category and resource closure state without raw exceptions or credentials
