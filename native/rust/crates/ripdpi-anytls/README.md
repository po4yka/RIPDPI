# ripdpi-anytls

AnyTLS client/outbound implementation for RIPDPI.

This crate implements the client side only and is wired into relay-core through `ripdpi-relay-tls-transports` as `RelayBackend::AnyTls` / `RelayBackendConfig::AnyTls`. The relay descriptor marks AnyTLS as TCP and UDP capable.

Non-goals are AnyTLS server/inbound mode and non-TLS transport substrates.
