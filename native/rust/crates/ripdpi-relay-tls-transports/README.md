# ripdpi-relay-tls-transports

**Responsibility:** relay-core adapters for TLS-shaped relay transports.

This crate is the adapter layer between protocol clients and `ripdpi-relay-core` factories. It exposes session factories and relay-stream wrappers for AnyTLS, Shadowsocks, ShadowTLS, Tor, and Trojan so relay-core can build pooled backends without duplicating protocol-specific connection code.

## Exported Adapters

- AnyTLS: `AnyTlsSessionFactory`, `AnyTlsSession`, `AnyTlsUdpSession`, and helper target conversion.
- Shadowsocks: `ShadowsocksSessionFactory`, `ShadowsocksSession`, `ShadowsocksUdpSession`, and helper target conversion.
- ShadowTLS: `ShadowTlsSessionFactory`, `ShadowTlsClientConfig`, and inner-relay configuration.
- Tor: `TorRelayBackend`, `TorRelayStream`, `TorRelayTarget`, bridge/PT config DTOs, and pluggable-transport config DTOs.
- Trojan: `TrojanSessionFactory`, `TrojanSession`, `TrojanUdpSession`, and helper target conversion.

## Boundaries

- NaiveProxy is not adapted here because it is supervised as an external helper subprocess.
- Snowflake, WebTunnel, and obfs4 are external pluggable transports managed by Kotlin service code, not relay-core TLS transport adapters.
- VLESS Reality and xHTTP live in their own crates because they have dedicated Reality/xHTTP behavior and Finalmask interactions.
