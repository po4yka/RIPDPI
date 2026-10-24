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

- **WHEN** an owned emulator, real native artifact or independent peer is unavailable
- **THEN** the selected runtime scenario fails or reports blocked without a success receipt
- **AND** existing unrelated devices, processes, credentials and worktrees remain unchanged

#### Scenario: Local USB transport has a separate scope

- **WHEN** a selected physical device has no common LAN with the owned peer host
- **THEN** a named USB scenario can use a test-only datagram bridge while retaining real VPN, native protocol and independent payload checks
- **AND** its result marks direct device-to-LAN UDP, underlying-network bypass and Wi-Fi/cellular handover as unverified
- **AND** the direct LAN scenarios retain their original assertions
