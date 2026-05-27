# Shadowsocks Step 0 Audit

This note is the pre-implementation gate for full Shadowsocks support. It records the normative protocol reading, current crate/test coverage, relay-core integration points, and the TDD plan that must be reviewed before any implementation slice starts.

## References Read

- SIP004 AEAD Ciphers, Shadowsocks org wiki: `https://github.com/shadowsocks/shadowsocks-org/wiki/AEAD-Ciphers`
- SIP002 URI Scheme, Shadowsocks org wiki: `https://github.com/shadowsocks/shadowsocks-org/wiki/SIP002-URI-Scheme`
- SIP022 AEAD-2022 Ciphers, Shadowsocks docs: `https://shadowsocks.org/doc/sip022.html`
- shadowsocks-rust repository, reference only for cross-checking behavior and fixture shape: `https://github.com/shadowsocks/shadowsocks-rust`

The SIPs are normative. `shadowsocks-rust` is only a reference implementation for comparison and must not be copied into this repository.

## Normative Constants

- SIP004 AEAD methods are `aes-128-gcm`, `aes-256-gcm`, and `chacha20-ietf-poly1305`; key/salt/nonce/tag sizes are 16/16/12/16 for AES-128-GCM and 32/32/12/16 for AES-256-GCM and ChaCha20-IETF-Poly1305.
- SIP004 master key derivation follows OpenSSL `EVP_BytesToKey` with MD5, and the per-session subkey is `HKDF_SHA1(key, salt, info = "ss-subkey")`.
- SIP004 TCP starts with a random salt, then encrypted chunks shaped as encrypted 2-byte big-endian payload length plus tag, then encrypted payload plus tag. The plaintext payload length is capped at `0x3FFF`; the nonce counter starts at zero and increments as an unsigned little-endian integer after every AEAD operation, so each chunk consumes two nonce values.
- SIP004 UDP is `[salt][encrypted payload][tag]`; each datagram is independent and uses an all-zero nonce with the salt-derived subkey.
- SIP002 `ss://` format is `ss://userinfo@hostname:port[/][?plugin][#tag]`, where `userinfo` is either Base64URL UTF-8 `method:password` or plain `method:password`; for AEAD-2022, Base64URL-encoded `userinfo` is forbidden and plain `method:password` must be percent-encoded.
- SIP002 plugin query arguments are SIP003/SIP003u territory and are out of scope for RIPDPI Shadowsocks support; they must not silently activate plugin behavior.
- SIP022 PSK is user-supplied base64 raw key material whose byte length must match the method; implementations MUST NOT use `EVP_BytesToKey` or any password-to-key derivation for AEAD-2022.
- SIP022 subkey derivation is BLAKE3 derive-key with context `shadowsocks 2022 session subkey` and input key material `psk || salt`.
- SIP022 required methods are `2022-blake3-aes-128-gcm` and `2022-blake3-aes-256-gcm`; optional methods include `2022-blake3-chacha20-poly1305`.
- SIP022 TCP inherits the AEAD length-chunk-payload-chunk model but adds standalone header chunks for request and response, carries a 1-byte stream type with request `0` and response `1`, uses the 2022 PSK/BLAKE3 KDF, requires replay protection, and lifts the payload cap to `0xFFFF`.
- SIP022 UDP does not use SIP004 `[salt][body]` framing. AES-GCM methods use an encrypted 16-byte separate header containing 8-byte session ID and 8-byte big-endian packet ID, derive the session subkey from `psk || separate_header[0..8]`, and seal the body with nonce `separate_header[4..16]`.
- SIP022 UDP main headers carry message type `0` for client packet and `1` for server packet, an 8-byte big-endian Unix timestamp, padding length, optional padding, SOCKS address, and port; server-to-client adds the client session ID.
- SIP022 UDP replay protection is mandatory: sessions are remembered for at least 60 seconds, timestamp skew over 30 seconds is replay, and incoming packet IDs are checked with a sliding window that must not update until header validation succeeds.
- SIP022 ChaCha20 UDP uses a distinct XChaCha20-Poly1305 construction with a 24-byte random nonce and merged session/packet IDs in the main header; it shares the replay window requirement.

## Existing Test Classification

- `tests/aead_legacy_vectors.rs` has SIP004 KDF and AEAD known-answer coverage for AES-128-GCM, AES-256-GCM, and ChaCha20-IETF-Poly1305, including tamper/wrong-password/different-salt checks. Gap: vector provenance should be kept independent of the implementation and documented as SIP004 constants; no relay-level use exists yet.
- `tests/aead_2022_vectors.rs` has SIP022 BLAKE3 KDF and AEAD known-answer coverage for AES-128-GCM, AES-256-GCM, and ChaCha20-Poly1305, plus PSK length/base64 rejection and family mismatch checks. Gap: these validate primitive KDF/AEAD only, not SIP022 TCP header chunks or UDP separate-header replay semantics.
- `tests/tcp_roundtrip.rs` covers TCP round trips, incomplete data buffering, little-endian nonce use, SIP004 `0x3FFF` chunk splitting, SIP022 `0xFFFF` chunk cap, partial payload retry behavior, and AES-128-GCM 2022 round trip. Current checkout includes pre-existing staged TCP changes for little-endian nonce and 2022 cap behavior. Gap: no SIP022 standalone header chunk, stream type, associated request salt, or replay salt pool checks yet.
- `tests/udp_roundtrip.rs` currently treats both SIP004 and SIP022 as `[salt][AEAD body]` independent datagrams. This is valid for SIP004 but not SIP022 AES-GCM or SIP022 ChaCha20 UDP. Gap: SIP022 UDP must be rewritten around separate headers, packet IDs, timestamps, padding, address headers, and replay windows.
- `tests/uri_parse.rs` covers SIP002 Base64URL userinfo, plain userinfo, legacy whole-URI base64, tag decode, IPv6, stream-cipher rejection, and basic malformed inputs. Gaps: AEAD-2022 Base64URL userinfo rejection, percent-decoded plain userinfo, plugin query parse/reject policy, Kotlin `ProxyUriCodec` parity, and one-source-of-truth runtime import are not covered.
- `tests/reject_stream_ciphers.rs` pins stream-cipher rejection and must remain green. No implementation slice may add stream cipher support or downgrade a rejected name.

## Current Implementation Gap Audit

- `src/cipher.rs` is mostly aligned for SIP004 and SIP022 KDF primitives: it rejects stream ciphers, uses `EVP_BytesToKey` plus HKDF-SHA1 for SIP004, validates SIP022 PSK length, and uses BLAKE3 derive-key for 2022 subkeys. Open checks: ensure `2022-blake3-chacha20-poly1305` is only advertised when TCP and UDP constructions are actually implemented and tested, and keep PSK handling separate from `SecretString` password semantics in relay-core.
- `src/tcp.rs` has SIP004-like chunk framing and, with current staged changes, uses little-endian nonces and separate `0x3FFF`/`0xFFFF` caps. Gaps: SIP022 request/response standalone header chunks, stream type byte, associated request salt, and replay salt storage are not modeled.
- `src/udp.rs` is SIP004-only framing under a method-agnostic API. Gaps: SIP022 AES-GCM separate-header encryption, main header construction/parsing, session ID and packet ID counters, timestamp validation, sliding replay window, and the distinct XChaCha20 UDP construction for optional ChaCha20 2022.
- `src/uri.rs` parses a useful SIP002 subset but does not enforce AEAD-2022 plain-userinfo-only semantics, does not percent-decode plain userinfo, does not expose plugin policy explicitly, and has no Kotlin parity contract.
- The crate is an orphan library today: no `ripdpi-relay-core` dependency edge consumes it, and no native relay backend can build Shadowsocks sessions.
- `README.md` already records intended support and non-goals; it must be updated after each completed slice to reflect implemented behavior, especially that legacy stream ciphers and SIP003/SIP003u plugins remain non-goals.

## Relay-Core Touch Points

- Follow the in-process backend pattern used by Trojan because it already covers TCP and UDP fixture E2E: add a `ShadowsocksRelayConfig` include under `src/config/backend/`, extend `RelayBackendConfig`, `RelayKind`, `config/conversions.rs`, `config/flat.rs`, `runtime_validation.rs`, `tests.rs`, and `transport_descriptor.rs`.
- Add a `ShadowsocksSessionFactory` under `src/protocols/` or the nearest existing transport-adapter module, backed by `ripdpi-shadowsocks` rather than moving Shadowsocks protocol logic into relay-core.
- Add a `RelayBackend::Shadowsocks` variant and route it through `dispatch_pooled_backend!`, `connect_tcp`, UDP capability checks, and `RelayUdpSession` send/receive.
- Add a `backend/builder/builders/shadowsocks.rs` builder that validates server port, cipher method, credential material, UDP enablement, and outbound bind behavior consistently with the descriptor.
- Add `ripdpi-shadowsocks` to `ripdpi-relay-core/Cargo.toml` only when the first relay-core red test is in place.
- Extend `local-network-fixture` with a minimal offline Shadowsocks server for SIP004 and SIP022 TCP/UDP E2E, similar in scope to `TrojanLoopback`, and use it from relay-core tests.
- Preserve `ripdpi-shadowsocks` as a protocol leaf crate; `ripdpi-relay-core` depends on it, not the other way around.

## Kotlin And Schema Touch Points

- Add a stable `RelayKindShadowsocks` string constant in the relay settings model and align the `app_settings.proto` field 171 comment, `RelayKindDescriptor`, resolver registry, feature-contract harness, and descriptor drift tests.
- Add Shadowsocks fields to `ResolvedRipDpiRelayConfig` and the section model in `core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RelayNativeConfig.kt`, then mirror the same fields in Rust `FlatResolvedRelayRuntimeConfig`.
- Bump `RelayNativeConfigSchemaVersion` and Rust `SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION` together because the wire DTO shape changes, and update `NativeConfigSchemaVersionTest`.
- Add a `ShadowsocksRelayKindResolver` only if default resolver cannot safely project method/password fields; otherwise document why the default resolver is sufficient and still add a descriptor-backed registration.
- Reconcile `ss://` import so Kotlin and Rust agree on SIP002 semantics. Kotlin `ProxyUriCodec` is currently the runtime import source; either keep Kotlin as source of truth and add Rust parity tests for `uri.rs`, or expose a native parser through a tested path. The chosen source must enforce AEAD-2022 non-Base64 userinfo and percent-decoding.
- Add credential persistence for Shadowsocks method/password or PSK through the existing relay credential path rather than reparsing `ss://` at runtime.

## TDD Test Plan

1. Cipher and KDF slice: add failing goldens that independently pin SIP004 `EVP_BytesToKey(MD5)` plus `HKDF_SHA1(..., "ss-subkey")`, SIP004 method sizes, SIP022 base64 PSK length validation, SIP022 `EVP_BytesToKey` rejection, and SIP022 BLAKE3 context `shadowsocks 2022 session subkey`; run `cargo test -p ripdpi-shadowsocks aead_legacy_vectors aead_2022_vectors reject_stream_ciphers`; implement only the failing primitive gaps; run `cargo test -p ripdpi-shadowsocks`, `cargo clippy -p ripdpi-shadowsocks --all-targets -- -D warnings`, and `cargo fmt --check`; commit as `test/feat(shadowsocks)` with body citing SIP004 key derivation and SIP022 section 2.
2. TCP framing slice: add failing tests for SIP004 `[salt][len][payload]` chunk framing, 2-byte big-endian length, `0x3FFF` SIP004 cap, little-endian nonce increments by two per chunk, incomplete reads that do not advance nonce state, SIP022 `0xFFFF` payload cap, SIP022 request stream type `0`, response stream type `1`, associated request salt in response header, and duplicate incoming 2022 salt replay rejection for at least 60 seconds; run `cargo test -p ripdpi-shadowsocks tcp_roundtrip`; implement the minimal TCP API changes; rerun full crate tests, clippy, and fmt; commit with body citing SIP004 TCP and SIP022 section 3.1 constants.
3. UDP framing and replay slice: add failing tests for SIP004 `[salt][AEAD payload]` all-zero nonce, SIP022 AES-GCM encrypted 16-byte separate header, session ID salt derivation from `separate_header[0..8]`, body nonce from `separate_header[4..16]`, packet ID `u64be`, message type `0`/`1`, timestamp skew rejection over 30 seconds, packet-ID duplicate/out-of-window rejection, replay state update only after successful header validation, and optional SIP022 ChaCha20 24-byte nonce construction if kept supported; run `cargo test -p ripdpi-shadowsocks udp_roundtrip`; implement minimal UDP/session/replay types; rerun full crate tests, clippy, and fmt; commit with body citing SIP004 UDP and SIP022 sections 3.2 and 4.1.
4. Relay-core slice: add failing relay-core tests for `RelayBackendConfig::Shadowsocks`, `RelayKind::Shadowsocks`, config JSON round trip including method/password or PSK fields, descriptor capabilities `(tcp=true, udp=true, reusable=false unless pooling is proven safe, supports_outbound_bind_ip=true if builder actually binds sockets)`, runtime validation of unsupported methods and missing credentials, backend builder dispatch, `RelayUdpSession` routing, and full TCP/UDP E2E against a new `local-network-fixture` Shadowsocks loopback; run `cargo test -p ripdpi-relay-core shadowsocks`; implement relay-core builder/protocol adapter/config/schema fields and fixture server; rerun `cargo test -p ripdpi-shadowsocks -p ripdpi-relay-core -p local-network-fixture`, clippy for touched crates, and fmt; commit as `feat(shadowsocks): wire relay-core backend` with body citing SIP004/SIP022 TCP+UDP clauses.
5. Kotlin import and native schema slice: add failing JVM tests for `ProxyUriCodec` SIP002 AEAD-2022 plain-userinfo-only behavior, percent-decoded PSK, plugin non-goal handling, Shadowsocks profile import into relay settings/credentials, `RelayKindDescriptor`/registry drift, `ResolvedRipDpiRelayConfig` Shadowsocks fields, and `NativeConfigSchemaVersionTest` bump; run focused Gradle tests for `core:data`, `core:service`, and `core:engine`; implement Kotlin resolver/descriptor/schema/import changes; rerun the focused tests plus `./gradlew staticAnalysis` if the slice touches lint-sensitive Kotlin; commit with body citing SIP002 and the native config schema migration.
6. Fixture and golden hardening slice: add non-tautological goldens through the existing `RIPDPI_BLESS_GOLDENS`/`golden-test-support` harness where wire bytes are derived from fixed SIP constants, not from newly generated implementation output; add local-network fixture coverage for TCP connect and UDP associate for at least one SIP004 method and both SIP022 required AES-GCM methods; run the fixture tests in the same command CI will use; commit with body citing the covered SIP constants.
7. Final verification slice: update `native/rust/crates/ripdpi-shadowsocks/README.md` with implemented support and non-goals, update architecture/config docs if drift tests require it, then run `cargo test -p ripdpi-shadowsocks -p ripdpi-relay-core -p local-network-fixture`, `cargo clippy -p ripdpi-shadowsocks -p ripdpi-relay-core -p local-network-fixture --all-targets -- -D warnings`, `cargo fmt --check`, `cargo deny check`, and focused Gradle tests including `NativeConfigSchemaVersionTest`; commit docs/verification only if changes were needed.

## Stop Rules

- No implementation starts until this Step 0 plan is reviewed.
- Every implementation slice starts with a failing test command captured in the work log before source changes.
- No `#[ignore]`, tautological goldens, mocked unit under test, deleted tests, stream-cipher support, plugin support, or "for now" stubs.
- Do not commit red. Each slice commit must be green for its stated cargo/Gradle scope.
