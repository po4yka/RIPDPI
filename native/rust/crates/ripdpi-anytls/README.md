# ripdpi-anytls

AnyTLS client/outbound implementation for RIPDPI.

This crate implements the client side only and is wired into relay-core through `ripdpi-relay-tls-transports` as `RelayBackend::AnyTls` / `RelayBackendConfig::AnyTls`. The relay descriptor marks AnyTLS as TCP and UDP capable.

**Upstream:** `ripdpi-tls-profiles` for the BoringSSL/TLS client path. **Downstream:** `ripdpi-relay-tls-transports`, then `ripdpi-relay-core`.

Non-goals are AnyTLS server/inbound mode and non-TLS transport substrates.
