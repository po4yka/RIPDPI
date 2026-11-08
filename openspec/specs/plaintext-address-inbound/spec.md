# plaintext-address-inbound Specification

## Purpose
Keep the local plaintext address input safe, reachable, and independent of TCP read boundaries while preserving its wire and access contracts.

## Requirements

### Requirement: REQ-PLAIN-POLICY — Gate application bytes after payload classification

The runtime MUST apply destination policy before forwarding client application bytes. It SHALL preserve the complete first TLS request across address and payload fragmentation. It MAY open a provisional connection under IP/default policy to support server-first traffic, but MUST recheck payload policy before the first upstream application write.

#### Scenario: Late blocked HTTP payload

- **WHEN** a plaintext IP header is followed later by an HTTP request whose Host is blocked
- **THEN** neither an origin nor an upstream receives application bytes in Mixed or BytePrefixed mode

#### Scenario: Fragmented first TLS payload

- **WHEN** a ClientHello is split across the address read and later reads within the assembly deadline
- **THEN** the complete ClientHello and SNI determine the policy and no blocked application bytes are sent

#### Scenario: Server-first and half-close

- **WHEN** a local origin sends a greeting before client payload and responds after client FIN
- **THEN** the greeting and final response arrive without a protocol acknowledgement or data loss

### Requirement: REQ-PLAIN-CANDIDATE — Reject final listener destinations

The runtime MUST reject a TCP candidate or upstream endpoint that resolves to its actual bound listener, including preferred and fallback candidates and canonical IPv4-mapped IPv6 forms. It MUST allow other local service ports for a local peer.

#### Scenario: Preferred edge targets the listener

- **WHEN** a foreign original destination is replaced by the loopback listener endpoint
- **THEN** no self-connection is accepted by the listener

#### Scenario: Legitimate local service

- **WHEN** a loopback destination uses a different service port
- **THEN** the request can exchange payload with that service

### Requirement: REQ-PLAIN-ACTIVATION — Explicit command-line activation

The shared command-line parser MUST accept --shadowsocks-plain to select the existing plaintext address input. The desktop CLI and Android command-line payload MUST use that selection. Android MUST NOT inherit SS_LOCAL_PORT or SS_PLUGIN_OPTIONS from the process environment. Plaintext input MUST retain loopback-only access and reject auth tokens.

#### Scenario: Shipped CLI plaintext request

- **WHEN** the CLI starts on loopback with --shadowsocks-plain and receives ATYP, address, big-endian port, and payload
- **THEN** the selected origin receives only the payload and its response reaches the client

#### Scenario: Android command-line conversion

- **WHEN** a command-line payload includes --shadowsocks-plain
- **THEN** its runtime config enables the plaintext input without a UI or wire schema change

#### Scenario: Incompatible access settings

- **WHEN** plaintext input is requested with non-loopback bind or an auth token
- **THEN** startup fails and no unauthenticated LAN listener is exposed
