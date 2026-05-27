# Shadowsocks STEP 0 Audit And Test Plan

Date: 2026-05-27

Scope: STEP 0 for full Shadowsocks support. This note records the normative protocol audit, existing `ripdpi-shadowsocks` test inventory, identified gaps, relay-core integration touch points, and the TDD test plan to execute after review. No protocol implementation is changed in this slice.

## References

- SIP002 URI Scheme: https://github.com/shadowsocks/shadowsocks-org/wiki/SIP002-URI-Scheme
- SIP004 AEAD Ciphers: https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers
- SIP022 AEAD-2022 Ciphers: https://shadowsocks.org/doc/sip022.html
- shadowsocks-rust: https://github.com/shadowsocks/shadowsocks-rust, used only as a behavioral cross-check and not as a source for copied code.

## Normative Constants

| Area | Normative source | Required behavior | Current status |
| --- | --- | --- | --- |
| SIP004 methods | SIP004 AEAD cipher table | Support `aes-128-gcm`, `aes-256-gcm`, and `chacha20-ietf-poly1305`; salt lengths are 16, 32, 32; nonce is 12 bytes; tag is 16 bytes. | Present in `cipher.rs`; round-trip tests exist but not fixed byte goldens. |
| SIP004 KDF | SIP004 Key Derivation | Password-derived master key uses OpenSSL-compatible `EVP_BytesToKey` with MD5, then per-session subkey uses HKDF-SHA1 with info string `ss-subkey`. | Present in `CipherKey::derive_legacy`; needs fixed vectors for EVP output, HKDF subkey, and AEAD ciphertext. |
| SIP004 TCP | SIP004 TCP | TCP starts with salt, then chunks: encrypted 2-byte big-endian length plus tag, encrypted payload plus tag; plaintext payload is capped at `0x3FFF`; nonce starts at zero and increments as an unsigned little-endian 96-bit integer after each AEAD operation. | Chunk shape and `0x3FFF` cap are partly present; nonce is implemented as big-endian in `counter_nonce`, and no boundary/partial-read/counter goldens exist. |
| SIP004 UDP | SIP004 UDP | UDP packet is salt plus encrypted payload plus tag; each packet uses a derived key and all-zero nonce. | Present for legacy AEAD; needs fixed byte vectors and parser failure coverage. |
| SIP022 PSK | SIP022 section 2.1 | 2022 methods require a user-supplied fixed-length base64 PSK; implementations must not use `EVP_BytesToKey` or any password-to-key fallback. | The crate decodes base64 PSKs for 2022, but API shape still passes `SecretString` and needs tests that reject non-base64 and wrong-length PSKs before any framing. |
| SIP022 KDF | SIP022 section 2.2 | Session subkey uses BLAKE3 derive-key with context `shadowsocks 2022 session subkey` and key material `key + salt`; salt length equals key length. | Present for AES-256/ChaCha; missing required `2022-blake3-aes-128-gcm`; needs fixed subkey vectors. |
| SIP022 required methods | SIP022 section 3 | Required methods are `2022-blake3-aes-128-gcm` and `2022-blake3-aes-256-gcm`; optional methods include `2022-blake3-chacha20-poly1305` variants. | AES-256 and ChaCha20 are present; AES-128 is missing, so the current method set is not SIP022-complete. |
| SIP022 TCP framing | SIP022 sections 3.1.1-3.1.5 | TCP request stream starts with salt and two standalone encrypted header chunks, response starts with salt and one fixed-length header chunk; payload cap is `0xFFFF`; stream type is 0 for request and 1 for response; timestamps older/newer than 30 seconds are replay; incoming request salts are stored for 60 seconds with no false-positive filter. | Current 2022 TCP uses SIP004-like chunks only; request/response headers, timestamps, salt replay protection, request-salt response binding, `0xFFFF` cap, and one-write header buffering are absent. |
| SIP022 UDP AES-GCM construction | SIP022 sections 3.2.1-3.2.4 | UDP packet has encrypted 16-byte separate header containing session ID and big-endian packet ID; body uses session subkey derived from PSK and session ID; body nonce is separate-header bytes 4..16; main header carries type 0/1 and timestamp; replay protection uses per-session sliding window and must update only after semantic validation. | Current 2022 UDP uses SIP004 salt-plus-payload framing; session IDs, packet IDs, encrypted separate headers, timestamp validation, and sliding-window replay protection are absent. |
| SIP022 optional ChaCha UDP | SIP022 section 4.1 | `2022-blake3-chacha20-poly1305` UDP uses XChaCha20-Poly1305 with a 24-byte random nonce and main-header session/packet IDs. | Current ChaCha 2022 UDP uses the same SIP004-shaped 12-byte nonce path; either implement optional construction correctly or keep it disabled for UDP until covered. |
| SIP002 URI | SIP002 URI Scheme | URI format is `ss://userinfo@hostname:port[/][?plugin][#tag]`; userinfo may be base64url for SIP004 but must not be base64url for SIP022; plain userinfo method and password must be percent encoded. | Rust and Kotlin both parse SIP002 separately; both need SIP022 plain percent-encoded userinfo coverage and explicit plugin rejection/non-support behavior. |

## Existing Test Inventory

`ripdpi-shadowsocks` currently has 651 lines of integration tests under `native/rust/crates/ripdpi-shadowsocks/tests/`: 113 lines for AEAD-2022 vectors, 105 for SIP004 AEAD vectors, 125 for stream-cipher rejection, 105 for TCP round trips, 78 for UDP round trips, and 125 for URI parsing.

The current tests are mostly self-consistent round trips: they prove encrypt/decrypt symmetry, authentication failure on tamper/wrong key for selected paths, stream cipher rejection, basic SIP002 forms, incomplete TCP chunks, and short UDP packet rejection. They do not yet prove byte-for-byte conformance to SIP004/SIP022 constants because the existing vectors are generated by the implementation under test rather than an independent oracle.

## Current Gap Audit

- `cipher.rs`: supports SIP004 AEAD methods and 2022 AES-256/ChaCha, but misses required `2022-blake3-aes-128-gcm`; 2022 PSK handling needs a typed raw-key path to make accidental `EVP_BytesToKey` use impossible; fixed KDF/AEAD vectors are missing.
- `tcp.rs`: implements one generic SIP004-style chunk stream for both SIP004 and SIP022; nonce construction is big-endian despite SIP004/SIP022 requiring unsigned little-endian 96-bit counters; no public coverage for boundary chunking at `0x3FFF`, 2022 payload cap `0xFFFF`, multiple chunks, exact bytes consumed, partial length vs partial payload buffering, or nonce rollover behavior.
- `udp.rs`: implements SIP004 UDP framing for both SIP004 and SIP022; SIP022 AES-GCM separate header, session ID routing, packet ID, timestamp, client/server type bytes, and replay window are missing.
- `uri.rs`: handles base64 and plain SIP002 userinfo, legacy whole-URI base64, tags, and IPv6, but 2022 plain percent-encoded userinfo and plugin non-goal behavior need explicit tests; Kotlin `ProxyUriCodec` duplicates parsing behavior and can drift from Rust.
- `lib.rs`: crate docs already list supported ciphers and stream-cipher rejection, but a README is needed to record implemented scope and non-goals.
- `relay-core`: `ripdpi-shadowsocks` is present in the workspace but not used by `ripdpi-relay-core`; there is no `RelayBackendConfig::Shadowsocks`, no `RelayKind::Shadowsocks`, no transport descriptor row, no backend builder/factory, no `RelayBackend::Shadowsocks`, no UDP session arm, and no flattened native config fields.
- Kotlin/runtime config: `RelayNativeConfigSchemaVersion` is 2; `ResolvedRipDpiRelayConfig` has no Shadowsocks method/password fields; `RelayKindDescriptors`, `RelaySettings`, `RelayKindResolverRegistry`, `NativeConfigSchemaVersionTest`, and the proto relay_kind comment need alignment.
- Import path: `ProxyUriCodec` and `ripdpi-shadowsocks::uri` parse `ss://` independently; the later implementation should make one source of truth for validation semantics, with parity tests if physical sharing is not feasible across Kotlin/Rust.

## Relay-Core Touch Points

The integration should mirror existing in-process backends, closest to Trojan for TCP+UDP over a single remote server and TUIC/Hysteria/MASQUE for UDP-capability plumbing.

- Add `ShadowsocksRelayConfig` in `native/rust/crates/ripdpi-relay-core/src/config/backend/shadowsocks.rs` with method, password or PSK, and any SIP022 replay/session policy knobs that must be explicit.
- Extend `RelayBackendConfig`, `RelayKind`, `kind_id`, `sample_config`, flat config serde conversion, and runtime round-trip tests with stable kind id `shadowsocks`.
- Add `RelayTransportDescriptor` row: TCP true, UDP true, reusable likely false until a pooled session design is proven, outbound-bind-IP true if the outbound TCP/UDP sockets use the same binding path as Trojan; finalmask unsupported.
- Add a builder under `backend/builder/builders/shadowsocks.rs` that creates a `ShadowsocksSessionFactory` from `ripdpi-shadowsocks`.
- Add `RelayBackend::Shadowsocks` and `RelayUdpSession::Shadowsocks` arms; `connect_tcp` opens a TCP connection to the Shadowsocks server and writes the encrypted target request, and `open_udp_session` exposes SIP004/SIP022 UDP encapsulation.
- Add `ripdpi-shadowsocks` as a relay-core dependency only when the backend slice begins; keep the crate orphaned as requested rather than folding protocol logic into relay-core.
- Extend local-network-fixture with a minimal offline Shadowsocks server for SIP004 and SIP022 so relay-core E2E does not depend on external infrastructure.

## Kotlin Touch Points

- Add `RelayKindShadowsocks = "shadowsocks"` and update normalization, proto comment, descriptor row, relay presets/draft support where required, and resolver registration.
- Add Shadowsocks fields to `ResolvedRipDpiRelayConfig` and section models, then bump both Kotlin `RelayNativeConfigSchemaVersion` and Rust `SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION` because the flat wire DTO gains new runtime fields.
- Add native-config schema tests that version 3 decodes and legacy/default omission behavior still holds, with Rust deserialization tests rejecting stale explicit schema versions.
- Reconcile `ss://` import so SIP002 handling for 2022 userinfo is consistent between Kotlin and Rust; short-term acceptable outcome is shared test vectors loaded by both test suites, while a longer-term single implementation boundary should be chosen before the Kotlin slice lands.

## TDD Slice Plan

Each slice starts with a failing test and the failing command output, then minimal implementation, green verification, refactor only while green, and one atomic Conventional Commit. Existing passing tests must remain in place; no `#[ignore]`, tautological goldens, or mocks of the unit under test.

### Slice 1: cipher and KDF conformance

1. Add independent fixed vectors for SIP004 `EVP_BytesToKey` MD5 output, HKDF-SHA1 `ss-subkey`, salt/key/tag lengths, and AEAD ciphertext/decryption for `aes-128-gcm`, `aes-256-gcm`, and `chacha20-ietf-poly1305`, citing SIP004 Key Derivation and AEAD table.
2. Add vectors for SIP022 PSK parsing, BLAKE3 subkey derivation with context `shadowsocks 2022 session subkey`, and required methods `2022-blake3-aes-128-gcm` and `2022-blake3-aes-256-gcm`, citing SIP022 sections 2.1, 2.2, and 3.
3. Add negative tests proving 2022 methods reject non-base64 and wrong-length PSKs without invoking legacy `EVP_BytesToKey`, citing SIP022 section 2.1.
4. Keep `reject_stream_ciphers.rs` green and add any missing stream aliases only if the test first exposes a gap.
5. Verification: `cd native/rust && cargo test -p ripdpi-shadowsocks --locked`, `cargo clippy -p ripdpi-shadowsocks --all-targets --locked -- -D warnings`, and `cargo fmt --check`.

### Slice 2: TCP framing conformance

1. Add SIP004 TCP goldens for salt plus encrypted chunks, 2-byte big-endian length, `0x3FFF` cap, partial length-frame buffering, partial payload-frame buffering, multiple chunks, exact bytes consumed, and unsigned little-endian 96-bit nonce increments by two per chunk, citing SIP004 TCP.
2. Add SIP022 TCP tests for request fixed header type 0, timestamp, variable header with SOCKS address and padding/initial payload rule, response fixed header type 1 plus request salt binding, `0xFFFF` payload cap, little-endian nonce, and stale timestamp/reused salt rejection, citing SIP022 sections 3.1.1-3.1.5.
3. Add a detection-prevention test that initial salt and standalone header chunks are emitted as one contiguous first write from the codec adapter boundary, citing SIP022 section 3.1.4.
4. Verification: `cd native/rust && cargo test -p ripdpi-shadowsocks --locked`, clippy, fmt.

### Slice 3: UDP framing and 2022 replay protection

1. Add SIP004 UDP fixed packet vectors for `[salt][encrypted payload][tag]` and all-zero nonce, citing SIP004 UDP.
2. Add SIP022 AES-GCM UDP tests for encrypted separate header layout, session ID, big-endian packet ID, BLAKE3 subkey from `key + session_id`, body nonce from separate header bytes 4..16, client/server type bytes 0/1, timestamp replay rejection, and server-session-ID distinction, citing SIP022 sections 3.2.1-3.2.4.
3. Add sliding-window replay tests for duplicate packet ID, out-of-window packet ID, and "do not update replay window before semantic validation", citing SIP022 section 3.2.4.
4. Decide optional `2022-blake3-chacha20-poly1305` UDP: either implement SIP022 section 4.1 with XChaCha20-Poly1305 vectors or reject UDP for that optional method with an explicit test and README note.
5. Verification: `cd native/rust && cargo test -p ripdpi-shadowsocks --locked`, clippy, fmt.

### Slice 4: native relay-core integration

1. Add failing relay-core config tests for `RelayBackendConfig::Shadowsocks`, `RelayKind::Shadowsocks`, flat JSON round-trip, descriptor coverage, planned TCP/UDP capabilities, and runtime validation with UDP enabled.
2. Add failing builder/session tests for `RelayBackend::Shadowsocks`, `ShadowsocksSessionFactory`, TCP target CONNECT framing, UDP session send/recv framing, and unsupported-method validation.
3. Add `local-network-fixture` minimal Shadowsocks server and offline E2E tests that tunnel TCP and UDP through relay-core against fixture targets for SIP004 and SIP022.
4. Verification: `cd native/rust && cargo test -p ripdpi-shadowsocks -p ripdpi-relay-core -p local-network-fixture --locked`, targeted relay fixture tests, clippy for touched crates, fmt.

### Slice 5: Kotlin runtime config and import-to-runtime path

1. Add failing Kotlin tests for `RelayKindShadowsocks`, `RelayKindDescriptor` drift, resolver registry coverage, `ResolvedRipDpiRelayConfig` Shadowsocks fields, and `NativeConfigSchemaVersionTest` version bump.
2. Add SIP002 parity tests for Kotlin `ProxyUriCodec` and Rust `uri.rs`: SIP004 base64 userinfo, SIP004 plain userinfo, SIP022 plain percent-encoded userinfo, plugin rejection/non-goal behavior, and unsupported stream cipher rejection, citing SIP002 and SIP022 section 2.1.
3. Wire imported `ProxyProfile.Shadowsocks` into relay settings/runtime resolution so `ss://` import can produce a native `shadowsocks` relay config with method, server, port, and credential material.
4. Verification: focused JVM tests for proxy import, relay resolver, descriptor drift, native schema version, plus Rust schema tests; then `./gradlew staticAnalysis` or the narrow module equivalent if the slice remains localized.

## Commit Policy For Later Slices

After STEP 0, each completed slice must end in one green Conventional Commit with scope `shadowsocks`, and the commit body must cite the SIP section(s) covered by that slice. Do not amend across slices.
