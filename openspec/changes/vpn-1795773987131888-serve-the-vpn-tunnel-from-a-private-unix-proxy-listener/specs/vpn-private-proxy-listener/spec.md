## Purpose

Define how the VPN tunnel and the in-app path clients reach the native proxy in VPN mode without a loopback listener, and which authentication, UDP, failure, and compatibility rules apply to that transport.

## ADDED Requirements

### Requirement: REQ-VPL-NO-LOOPBACK — No loopback listener for the tunnel-to-proxy hop

In VPN mode with the native proxy provider, when encrypted DNS is not routed through the proxy, the app process MUST NOT own a listening TCP socket bound to a loopback or wildcard address for the tunnel-to-proxy hop.

#### Scenario: Native VPN session exposes no loopback listener

- **GIVEN** VPN mode runs with the native proxy provider, no relay, WARP, or AmneziaWG upstream, and no encrypted DNS routed through the proxy
- **WHEN** a client enumerates the sockets of the app process
- **THEN** no socket bound to a loopback or wildcard address accepts a TCP connection.

### Requirement: REQ-VPL-PRIVATE-PATH — Session socket in the private files directory

The listener socket MUST be a file in the app-private files directory with a new random name for each session and owner-only permissions. The stop sequence MUST remove the file. The next start MUST remove files that a killed process left. The path MUST NOT appear in logs, exports, or reports.

#### Scenario: Stale socket file after a process kill

- **GIVEN** the process was killed while a VPN session ran
- **WHEN** the next VPN session starts
- **THEN** the old socket file is removed and the new session listens on a different name.

#### Scenario: Diagnostics export

- **GIVEN** a VPN session runs
- **WHEN** the user exports a diagnostics archive
- **THEN** the archive contains no listener socket path.

### Requirement: REQ-VPL-TCP — TCP flows through the Unix listener

The tunnel MUST carry each TCP flow to the proxy through the Unix listener with the same half-close behavior, per-app identity, and telemetry as the TCP listener path. A Unix stream cannot carry a reset, so a relay that the proxy aborts MUST reach the app as an end of stream; this is the one accepted difference from the TCP listener path, where the app receives a reset.

#### Scenario: TCP round trip with half-close

- **GIVEN** a routed app opens a TCP connection through the tunnel
- **WHEN** the app sends a request and closes its write side
- **THEN** the upstream receives the request and the end of stream, and the app receives the full response.

### Requirement: REQ-VPL-UDP — UDP flows over a passed datagram socket

The tunnel MUST carry UDP flows through the private UDP association on the Unix listener. The client MUST pass one `AF_UNIX` datagram socket to the proxy. The datagram framing, nonce, identifier rules, and remote-error feedback MUST stay identical to the loopback private association. The Unix listener MUST refuse the standard UDP ASSOCIATE command.

#### Scenario: UDP round trip and remote error

- **GIVEN** a routed app sends UDP datagrams through the tunnel
- **WHEN** the remote answers one datagram and an ICMP error arrives for another
- **THEN** the app receives the answer and the matching ICMP error.

#### Scenario: Invalid passed descriptor

- **GIVEN** a client completes the private association request
- **WHEN** it passes no descriptor, more than one descriptor, or a descriptor that is not an `AF_UNIX` datagram socket
- **THEN** the proxy closes every received descriptor, closes the control stream, and creates no association.

#### Scenario: Standard UDP ASSOCIATE

- **WHEN** a client sends the standard UDP ASSOCIATE command on the Unix listener
- **THEN** the proxy replies with "command not supported" and binds no UDP socket.

#### Scenario: Proxy aborts a frozen relay

- **GIVEN** a TCP flow runs through the Unix listener and the proxy detects a frozen upstream relay
- **WHEN** the proxy aborts the client side
- **THEN** the app observes the end of the stream and no further data.

### Requirement: REQ-VPL-DNS-TCP — Proxied encrypted DNS keeps the TCP listener

When encrypted DNS is routed through the proxy, the VPN session MUST use the loopback TCP listener with the session token, as before this change, because the encrypted DNS client cannot dial a Unix socket. The tunnel MUST refuse to start with a Unix upstream and proxied encrypted DNS together. Plain DNS that is routed through the proxy uses the tunnel flows and works with the Unix upstream.

#### Scenario: Encrypted DNS routed through the proxy

- **GIVEN** the DNS plan routes encrypted DNS through the proxy
- **WHEN** a VPN session starts with the native proxy provider
- **THEN** the proxy binds the token-protected loopback TCP listener and the tunnel uses it.

#### Scenario: Unix upstream with proxied encrypted DNS

- **WHEN** the tunnel receives a config with a Unix upstream and proxied encrypted DNS
- **THEN** the tunnel rejects the config and does not start.

### Requirement: REQ-VPL-AUTH — Authentication on the Unix listener

The Unix listener MUST require the session credentials. It MUST refuse SOCKS5 without authentication, SOCKS4, HTTP proxy requests without the session credentials, and transparent requests.

#### Scenario: Anonymous client on the Unix listener

- **WHEN** a client in the app process connects to the socket and sends a SOCKS5 greeting that offers only "no authentication"
- **THEN** the proxy refuses the method and opens no upstream connection.

### Requirement: REQ-VPL-INAPP — In-app path clients use the Unix listener

Onboarding validation, the onboarding volume check, the selector payload probe, the field report upload, and the in-path diagnostics scan MUST reach the active tunnel path through the Unix listener, including HTTPS requests. When the route is not available, each client MUST fail as it does today. No client may fall back to a TCP listener.

#### Scenario: HTTPS request through the Unix listener

- **GIVEN** a VPN session runs with the native proxy provider
- **WHEN** an in-app path client sends an HTTPS request
- **THEN** the request leaves through the proxy and the client receives the response.

#### Scenario: Route not available

- **GIVEN** no VPN session runs
- **WHEN** an in-app path client asks for the route
- **THEN** the client reports that the route is not available and opens no socket.

### Requirement: REQ-VPL-COMPAT — Other modes keep their listeners

Proxy mode MUST keep its TCP listener and its LAN authentication rules. The Xray provider MUST keep its loopback inbound and credentials. Relay, WARP, AmneziaWG, and helper-process listeners MUST keep their current behavior.

#### Scenario: Proxy mode

- **WHEN** the user starts proxy mode on a configured port
- **THEN** the proxy accepts SOCKS5 on that TCP port as before.

#### Scenario: Xray provider

- **WHEN** the user starts VPN mode with the Xray provider
- **THEN** the tunnel reaches the Xray inbound over loopback TCP with the session credentials.

### Requirement: REQ-VPL-CONTRACT — Tunnel upstream config contract

The tunnel config payload MUST use schema version 4. A payload MUST name either a TCP upstream (address and port) or a Unix upstream (path). Rust MUST reject a payload that names both, neither, or an older schema version. Kotlin and Rust MUST agree on the field names.

#### Scenario: Payload names both upstream forms

- **WHEN** the tunnel receives a payload with an address, a port, and a Unix path
- **THEN** the tunnel rejects the payload and does not start.

### Requirement: REQ-VPL-FAILSTART — Fail closed when the socket cannot be bound

When the proxy cannot bind the Unix listener, the VPN start MUST fail with a classified error. The app MUST NOT bind a TCP listener as a fallback.

#### Scenario: Path already exists

- **GIVEN** a file already exists at the session socket path
- **WHEN** the proxy starts
- **THEN** the start fails, the service reports the failure, and no TCP listener is bound.
