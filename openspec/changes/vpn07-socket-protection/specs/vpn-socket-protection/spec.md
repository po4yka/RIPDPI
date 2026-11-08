## Purpose

Keep outbound sockets outside the active VPN and preserve socket and service ownership when protection fails, stalls, or is cancelled.

## ADDED Requirements

### Requirement: REQ-VPN07-PROXY — Apply captured VPN policy to proxy sockets

The proxy MUST apply its captured VPN protection policy to each outbound non-loopback TCP and UDP socket before bind or connect. Protection failure MUST close the socket and prevent outbound I/O. An inactive proxy or deliberate callback-free RAW_PATH scan MUST retain its existing behavior.

#### Scenario: UI runtime carries the active protection path

- **WHEN** UI runtime context contains a VPN protection path and the protector denies a socket
- **THEN** ordinary proxy TCP, UDP, upstream SOCKS and probe constructors refuse outbound I/O

### Requirement: REQ-VPN07-UDS-BOUNDS — Bound local protection requests

Subprocess and WebSocket protection clients MUST bound connect, send and ACK waits. Cancellation MUST retain valid socket ownership until the blocking operation ends and MUST NOT protect a reused descriptor.

#### Scenario: The local accept queue is full

- **WHEN** a protection request reaches a Unix listener with a full accept queue
- **THEN** it returns an error within the documented step budget without connecting its outbound socket

### Requirement: REQ-VPN07-CLEANUP — Retain ownership through terminal cleanup

Session teardown MUST preserve pending native cleanup ownership until socket-opening workers have stopped. Stop failures MUST preserve the original failure and retain a retry owner when native completion is unconfirmed. Protection release MUST require an explicit FullyReleased outcome. Old session cleanup MUST NOT release a new session's registration.

#### Scenario: Native cleanup is pending

- **WHEN** stop returns CleanupPending
- **THEN** the owner retains the resources and retry path needed for safe terminal cleanup

#### Scenario: Stop raises an unexpected exception

- **WHEN** stop fails before cleanup completes
- **THEN** the original failure remains observable, protection stays owned, and a later successful stop completes teardown

### Requirement: REQ-VPN07-PROFILE-CHECK — Protect active VPN profile checks

A Naive profile check under active VPN policy MUST capture the current protection lease and pass that protection to the helper. Missing or stale required protection MUST reject the check. Inactive mode MUST remain usable without a protection endpoint.

#### Scenario: A profile check starts while VPN is active

- **WHEN** the user checks a Naive profile with VPN protection required
- **THEN** its helper uses the active protection endpoint and refuses unprotected egress on failure

### Requirement: REQ-VPN07-UDP-ORDER — Protect UDP before bind

VPN-required UDP factories MUST protect each new or replacement socket before bind, connect or send, including DNS, QUIC, Shadowsocks and WireGuard carriers. Protection rejection MUST drop the unbound socket.

#### Scenario: A carrier callback rejects protection

- **WHEN** a VPN-required carrier socket is passed to its protector
- **THEN** its local port is still zero and rejection prevents bind and network I/O

### Requirement: REQ-VPN07-SERVER-BOUNDS — Bound handler lifetime and close received descriptors

The protection server MUST limit active and queued sessions and bound handshake reads. Every received ancillary descriptor MUST close on success, denial, exception or shutdown. The endpoint MUST remain in app-private storage with session ownership.

#### Scenario: Both handlers receive clients that send no handshake

- **WHEN** the handshake deadline expires
- **THEN** both handlers release their sessions and a valid request can complete without server restart

#### Scenario: A batch handler fails after receiving descriptors

- **WHEN** extraction or protection raises an unexpected error
- **THEN** all descriptors received in that batch are closed and no positive ACK is sent
