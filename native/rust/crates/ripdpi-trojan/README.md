# ripdpi-trojan

Trojan outbound client library for RIPDPI relay-core integration.

## Protocol Notes

- Source of truth: the Trojan protocol page at `https://trojan-gfw.github.io/trojan/protocol.html`; reimplementations are cross-checks only.
- Reference-only cross-checks used for planning: `cfal/shoes` `src/trojan_handler.rs` and `p4gefau1t/trojan-go` `tunnel/trojan/packet.go`.
- A Trojan client first completes a real TLS handshake; all Trojan protocol bytes are then sent inside the TLS stream.
- The first bytes after TLS are `hex(SHA224(password))`, exactly 56 lowercase ASCII hex characters, followed by `CRLF`, the Trojan request, another `CRLF`, and then optional payload bytes in the same stream.
- The Trojan request is SOCKS5-like: `CMD ATYP DST.ADDR DST.PORT`, where `CMD` is `0x01` for CONNECT or `0x03` for UDP ASSOCIATE, `ATYP` is `0x01` for IPv4, `0x03` for domain name, or `0x04` for IPv6, and `DST.PORT` is two bytes in network byte order.
- Domain addresses use a single length byte followed by the domain bytes; this library must reject domains longer than 255 bytes before writing a frame.
- For UDP ASSOCIATE, each datagram inside the TLS stream is framed as `ATYP DST.ADDR DST.PORT Length CRLF Payload`, where `Length` is two bytes in network byte order.
- The spec hash vector is password `123456789` -> `462c1c933bf971099ac771e783855ad8763548b6b5854b09864a5344`.

## Non-Goals

- Browser-identical TLS fingerprint parity is out of scope. A BoringSSL-backed Chrome-like ClientHello is acceptable; exact Chromium parity is not required.
- Trojan-Go extensions are out of scope, including mux, WebSocket transport, and gRPC transport.

## Current Gap Audit

- `src/lib.rs` has `hash_password`, `encode_addr`, `build_request`, and `TrojanClient::write_request` for an already-established stream.
- Existing tests check shape and internal consistency, but they do not pin the normative `123456789` SHA224 vector and several tests compute expected hashes with the same implementation dependency they exercise.
- There is no UDP ASSOCIATE datagram codec, parser, or golden vector coverage.
- There is no TLS client, no SNI/ALPN/certificate-verification path, and no BoringSSL integration.
- There is no full CONNECT tunnel integration test against an offline TLS Trojan fixture.
- `ripdpi-relay-core` does not depend on `ripdpi-trojan`, has no `RelayBackendConfig::Trojan`, no `RelayKind::Trojan`, no `RelayBackend::Trojan`, no `TrojanSessionFactory`, no `config/trojan_inner.rs`, and no transport registration row.
- Kotlin has `ProxyUriCodec.parseTrojan()` producing `ProxyProfile.Trojan`, but runtime relay config does not yet project that parsed profile into `RelayKind::Trojan` or native relay credentials.
- `ResolvedRipDpiRelayConfig` / Rust `FlatResolvedRelayRuntimeConfig` schema version is currently `1`; adding Trojan credential/config fields is a native config schema migration and must be covered by `NativeConfigSchemaVersionTest`.

## Relay-Core Touchpoints

- Add `ripdpi-trojan` as a `ripdpi-relay-core` dependency in `native/rust/crates/ripdpi-relay-core/Cargo.toml`.
- Add `TrojanRelayConfig` under `src/config/backend/trojan.rs`, include it from `src/config/backend.rs`, add `RelayBackendConfig::Trojan`, and map `kind_id()` to `trojan`.
- Add `RelayKind::Trojan` in `src/config/kind.rs`; it should not support finalmask unless a later spec-backed reason appears.
- Add `trojan_password` and any needed Trojan-specific fields to `src/config/flat.rs` and map them in `src/config/conversions.rs`.
- Add `src/backend/builder/builders/trojan.rs`, export it from `builders/mod.rs`, and register it in `src/transport_descriptor.rs` with TCP and UDP capability true once UDP ASSOCIATE is implemented.
- Add a pooled `TrojanSessionFactory` and session under `src/protocols/trojan.rs`, mirroring the ShadowTLS factory shape but using Trojan directly over TLS instead of ShadowTLS plus inner VLESS.
- Extend `src/backend.rs` dispatch macros and UDP handling with a Trojan backend variant; `open_udp_session()` must return a Trojan stream-datagram session once the UDP codec is in place.
- Extend `src/runtime_validation.rs` pool sizing, planned capabilities coverage, and unsupported-feature checks.
- Extend `src/tests.rs` sample configs, kind lists, round-trip tests, transport descriptor tests, runtime validation tests, and backend build tests to include `trojan`.

## Kotlin Touchpoints

- Add `RelayKindTrojan = "trojan"` to `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/RelaySettings.kt`, normalize it, and update `app_settings.proto` field 171 comments if required by existing contract tests.
- Add Trojan password storage on the existing relay credential path instead of reparsing `trojan://`; `ProxyUriCodec.parseTrojan()` remains the import parser.
- Add Trojan fields to `core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RelayNativeConfig.kt`, section regrouping, and `toResolvedConfig()`.
- Bump `RelayNativeConfigSchemaVersion` and Rust `SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION` together, then update `NativeConfigSchemaVersionTest` expectations for the new schema behavior.
- Add a `TrojanRelayKindResolver`, register it in `RelayKindResolverRegistry`, and add a descriptor row in `RelayKindDescriptor`.
- Update import/runtime tests so a `trojan://password@host:443#name` profile resolves to `RelayKindTrojan`, carries the parsed password through credentials, and emits a native relay config that Rust accepts.

## Test Plan

1. Request codec red test: add golden byte-vector tests for `hash_password("123456789")` and full CONNECT frames for IPv4, domain, and IPv6 targets, citing the spec hash prelude, CRLF delimiters, `CMD`, `ATYP`, domain length, and network-order port clauses.
2. Request codec implementation: replace tautological hash tests with fixed expected values, expose a stable request encoder, reject overlong domains, run `cargo test -p ripdpi-trojan request` red/green, then run `cargo fmt` and `cargo clippy -p ripdpi-trojan --all-targets -- -D warnings`.
3. UDP codec red test: add golden byte-vector tests for UDP ASSOCIATE request headers and per-packet frames `ATYP DST.ADDR DST.PORT Length CRLF Payload` for IPv4, domain, and IPv6 targets, citing the spec UDP packet table.
4. UDP codec implementation: add encoder/decoder for stream datagrams with explicit CRLF and length validation, run focused `cargo test -p ripdpi-trojan udp` red/green, then fmt and clippy.
5. TLS CONNECT red test: add a local TLS Trojan server fixture using `local-network-fixture` patterns and assert SNI, ALPN, certificate verification, first-payload coalescing, and CONNECT echo behavior, citing the spec TLS-first and payload-after-request clauses.
6. TLS CONNECT implementation: add BoringSSL-backed client construction with SNI, ALPN, and cert verification, then run `cargo test -p ripdpi-trojan connect` and the local fixture E2E.
7. Relay-core red test: add tests that `trojan` is in the relay kind list, config round-trips through `ResolvedRelayRuntimeConfig`, UDP planned capability is true, backend build returns `RelayBackend::Trojan`, and runtime validation accepts UDP ASSOCIATE.
8. Relay-core implementation: add `RelayBackendConfig::Trojan`, `RelayKind::Trojan`, `TrojanSessionFactory`, builder, transport descriptor row, config schema fields, and UDP session plumbing; run `cargo test -p ripdpi-relay-core trojan`, `cargo clippy -p ripdpi-relay-core --all-targets -- -D warnings`, and `cargo fmt`.
9. Kotlin red test: add tests for `ProxyUriCodec` reuse, Trojan credential persistence, resolver registration/descriptor drift, `ResolvedRipDpiRelayConfig` Trojan fields, and `NativeConfigSchemaVersionTest` schema bump.
10. Kotlin implementation: wire `RelayKindTrojan`, credentials, resolver, descriptor, native config fields, and runtime projection without rewriting the existing `trojan://` parser; run the focused Gradle tests named by the failing cases.
11. Final verification: run `cargo test -p ripdpi-trojan -p ripdpi-relay-core`, `cargo clippy -p ripdpi-trojan -p ripdpi-relay-core --all-targets -- -D warnings`, `cargo deny check`, `cargo fmt --check`, and the focused Gradle relay/import/schema tests; update this README only for implemented behavior and retained non-goals.

## Commit Slices

1. `feat(trojan): implement request frame codec`
2. `feat(trojan): implement udp associate packet codec`
3. `feat(trojan): add tls connect client`
4. `feat(trojan): wire native relay backend`
5. `feat(trojan): wire kotlin relay runtime import`
