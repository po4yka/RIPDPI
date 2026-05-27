# ripdpi-shadowsocks

`ripdpi-shadowsocks` owns Shadowsocks protocol primitives for RIPDPI native relay integration. The crate is intentionally protocol-focused and remains an orphan library crate; relay-core integration should depend on it rather than moving cipher or framing logic into relay-core.

## Intended Support

- SIP004 AEAD methods: `aes-128-gcm`, `aes-256-gcm`, and `chacha20-ietf-poly1305`.
- SIP022 AEAD-2022 methods: `2022-blake3-aes-128-gcm`, `2022-blake3-aes-256-gcm`, and optional `2022-blake3-chacha20-poly1305` only where its SIP022 construction is explicitly implemented and tested.
- TCP and UDP framing for supported methods.
- SIP022 replay protection for TCP salts and UDP packet windows.
- SIP002 `ss://` import/export semantics needed by the Android runtime path.

## Non-Goals

- Legacy stream ciphers are not supported and must stay rejected. This includes `rc4`, `rc4-md5`, `aes-*-cfb`, `aes-*-ctr`, `camellia-*-cfb`, `chacha20`, `chacha20-ietf`, `salsa20`, `xchacha20`, and similar pre-AEAD methods; keep `tests/reject_stream_ciphers.rs` green.
- SIP003 and SIP003u plugins are not supported in this crate. Plugin query parameters such as obfs, simple-obfs, and v2ray-plugin must not silently change runtime behavior.
- This crate does not own Kotlin runtime resolution, Android UI, or relay process lifecycle. Those layers should pass validated method, server, port, and credential material into relay-core.

## Current Status

The cipher layer has SIP004/SIP022 fixed-vector coverage for KDF and AEAD operations. TCP, UDP, replay protection, URI parity, and relay-core wiring are still tracked in `docs/native/shadowsocks-step0-audit.md`.
