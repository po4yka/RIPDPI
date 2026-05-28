# ripdpi-shadowsocks

`ripdpi-shadowsocks` owns Shadowsocks protocol primitives for RIPDPI native relay integration. The crate is protocol-focused; relay-core integration depends on it through `ripdpi-relay-tls-transports` rather than moving cipher or framing logic into relay-core.

## Intended Support

- SIP004 AEAD methods: `aes-128-gcm`, `aes-256-gcm`, and `chacha20-ietf-poly1305`.
- SIP022 AEAD-2022 methods: `2022-blake3-aes-128-gcm`, `2022-blake3-aes-256-gcm`, and `2022-blake3-chacha20-poly1305`.
- TCP and UDP framing for supported methods.
- SIP022 replay protection for TCP salts and UDP packet windows.
- SIP002 `ss://` import/export semantics needed by the Android runtime path.

## Non-Goals

- Legacy stream ciphers are not supported and must stay rejected. This includes `rc4`, `rc4-md5`, `aes-*-cfb`, `aes-*-ctr`, `camellia-*-cfb`, `chacha20`, `chacha20-ietf`, `salsa20`, `xchacha20`, and similar pre-AEAD methods; keep `tests/reject_stream_ciphers.rs` green.
- SIP003 and SIP003u plugins are not supported in this crate. Plugin query parameters such as obfs, simple-obfs, and v2ray-plugin must not silently change runtime behavior.
- This crate does not own Kotlin runtime resolution, Android UI, or relay process lifecycle. Those layers should pass validated method, server, port, and credential material into relay-core.

## Current Status

The cipher layer has SIP004/SIP022 fixed-vector coverage for KDF and AEAD operations. TCP chunk framing covers SIP004 and SIP022 payload caps, nonce counters, and partial-frame behavior. UDP framing covers SIP004 round trips, SIP022 AES-GCM separate headers, SIP022 ChaCha20-Poly1305 XChaCha packet shape, server-to-client client-session IDs, and per-session replay filtering. Native relay-core wiring is implemented through `RelayBackend::Shadowsocks`, `RelayBackendConfig::Shadowsocks`, `ShadowsocksSessionFactory`, and the local-network fixture oracle. Android import/runtime wiring accepts supported SIP002 `ss://` methods, rejects legacy stream-cipher imports before runtime, carries `RelayNativeConfigSchemaVersion = 6`, and projects method/password credentials into the native relay config.
